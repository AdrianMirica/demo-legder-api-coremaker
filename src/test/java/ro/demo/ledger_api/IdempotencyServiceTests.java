package ro.demo.ledger_api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.mysql.MySQLContainer;
import ro.demo.ledger_api.idempotency.IdempotencyRepository;
import ro.demo.ledger_api.idempotency.IdempotencyService;
import ro.demo.ledger_api.idempotency.RequestHasher;
import ro.demo.ledger_api.idempotency.StoredHttpResponse;
import ro.demo.ledger_api.shared.api.ApiErrorCode;
import ro.demo.ledger_api.shared.api.ApiException;
import ro.demo.ledger_api.shared.api.ApiProblems;
import ro.demo.ledger_api.shared.api.ApiViolation;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class IdempotencyServiceTests {
	private static final String OPERATION = "OPEN_ACCOUNT";
	private static final StoredHttpResponse CREATED =
			new StoredHttpResponse(201, "application/json", "{ \"id\" : \"one\", \"amount\" : 125.50 }");

	@Autowired
	private IdempotencyService service;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private ApplicationContext context;
	@Autowired
	private ObjectMapper mapper;
	@Autowired
	private MySQLContainer mysql;

	@BeforeEach
	void resetTestData() {
		jdbc.execute("CREATE TABLE IF NOT EXISTS idempotency_test_effects (id INT PRIMARY KEY)");
		jdbc.update("DELETE FROM idempotency_test_effects");
		jdbc.update("DELETE FROM idempotency_records");
	}

	@Test
	void persistsExactResponseAndReplaysWithoutCallingBusinessLogic() {
		AtomicInteger calls = new AtomicInteger();
		StoredHttpResponse first = service.execute(OPERATION, "retry", Map.of("currency", "EUR"), () -> {
			calls.incrementAndGet();
			jdbc.update("INSERT INTO idempotency_test_effects VALUES (1)");
			return CREATED;
		});
		StoredHttpResponse replay = secondInstance().execute(OPERATION, "retry",
				Map.of("currency", "EUR"), () -> {
					throw new AssertionError("Retry must not execute business logic");
				});

		assertEquals(CREATED, first);
		assertEquals(first, replay);
		assertEquals(1, calls.get());
		assertEquals(1, effectCount());
		assertEquals(1, recordCount());
		assertEquals(CREATED.body(), jdbc.queryForObject(
				"SELECT response_body_text FROM idempotency_records", String.class));
	}

	@Test
	void changedRequestIsRejectedAndDoesNotOverwriteOriginalResponse() {
		service.execute(OPERATION, "conflict", Map.of("currency", "EUR"), () -> CREATED);

		ApiException exception = assertThrows(ApiException.class,
				() -> service.execute(OPERATION, "conflict", Map.of("currency", "USD"), () -> CREATED));
		assertEquals(ApiErrorCode.IDEMPOTENCY_KEY_REUSED, exception.errorCode());
		assertEquals(HttpStatus.CONFLICT, exception.status());
		assertEquals(CREATED, service.execute(OPERATION, "conflict",
				Map.of("currency", "EUR"), () -> {
					throw new AssertionError("Original response must be replayed");
				}));
	}

	@Test
	void operationScopeAndCaseSensitiveKeysAreIndependent() {
		service.execute(OPERATION, "Key", null, () -> CREATED);
		service.execute(OPERATION, "key", null, () -> CREATED);
		service.execute(OPERATION, "key ", null, () -> CREATED);
		service.execute("RECORD_TRANSACTION", "Key", null, () -> CREATED);
		assertEquals(4, recordCount());
	}

	@Test
	void unexpectedFailureRollsBackEffectsAndReservationAndAllowsRetry() {
		assertThrows(IllegalStateException.class, () ->
				service.execute(OPERATION, "failure", null, () -> {
					jdbc.update("INSERT INTO idempotency_test_effects VALUES (1)");
					throw new IllegalStateException("Simulated crash");
				}));
		assertEquals(0, recordCount());
		assertEquals(0, effectCount());
		assertEquals(CREATED, service.execute(OPERATION, "failure", null, () -> CREATED));
	}

	@Test
	void domainRejectionRollsBackPartialEffectsButPersistsAndReplaysProblem() {
		ApiException rejection = new ApiException(ApiErrorCode.INVALID_AMOUNT_SCALE, "Invalid amount",
				List.of(new ApiViolation("entries[0].amount", "INVALID_AMOUNT_SCALE", "Too many decimals")));
		StoredHttpResponse first = service.execute(OPERATION, "rejected", null, () -> {
			jdbc.update("INSERT INTO idempotency_test_effects VALUES (1)");
			throw rejection;
		});
		assertEquals(400, first.status());
		assertEquals("application/problem+json", first.contentType());
		assertEquals(mapper.writeValueAsString(ApiProblems.from(rejection)), first.body());
		assertEquals(0, effectCount());
		assertEquals(1, recordCount());
		assertEquals(first, secondInstance().execute(OPERATION, "rejected", null, () -> {
			throw new AssertionError("Rejected request must be replayed");
		}));
	}

	@Test
	void normalizedDecimalRetryReplaysOriginalResponse() {
		service.execute(OPERATION, "decimal", Map.of("amount", new BigDecimal("125.50")), () -> CREATED);
		assertEquals(CREATED, service.execute(OPERATION, "decimal",
				Map.of("amount", new BigDecimal("125.5")), () -> {
					throw new AssertionError("Equivalent decimals must hash identically");
				}));
	}

	@Test
	void concurrentInstancesExecuteBusinessCallbackOnlyOnce() throws Exception {
		IdempotencyService other = secondInstance();
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch ownerEntered = new CountDownLatch(1);
		CountDownLatch releaseOwner = new CountDownLatch(1);
		CountDownLatch retryStarted = new CountDownLatch(1);
		AtomicInteger calls = new AtomicInteger();
		try {
			var first = executor.submit(() -> service.execute(OPERATION, "race", null, () -> {
				calls.incrementAndGet();
				jdbc.update("INSERT INTO idempotency_test_effects VALUES (1)");
				ownerEntered.countDown();
				await(releaseOwner);
				return CREATED;
			}));
			assertTrue(ownerEntered.await(10, TimeUnit.SECONDS));
			assertEquals(0, recordCount(), "An in-progress reservation must not be visible");
			assertEquals(0, effectCount(), "Business changes must not commit before the response");
			var retry = executor.submit(() -> {
				retryStarted.countDown();
				return other.execute(OPERATION, "race", null, () -> {
					calls.incrementAndGet();
					return CREATED;
				});
			});
			assertTrue(retryStarted.await(10, TimeUnit.SECONDS));
			assertThrows(TimeoutException.class, () -> retry.get(200, TimeUnit.MILLISECONDS));
			releaseOwner.countDown();

			assertEquals(CREATED, first.get(10, TimeUnit.SECONDS));
			assertEquals(CREATED, retry.get(10, TimeUnit.SECONDS));
			assertEquals(1, calls.get());
			assertEquals(1, recordCount());
			assertEquals(1, effectCount());
		}
		finally {
			releaseOwner.countDown();
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
		}
	}

	@Test
	void waitingRetryBecomesOwnerWhenOriginalTransactionRollsBack() throws Exception {
		IdempotencyService other = secondInstance();
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch ownerEntered = new CountDownLatch(1);
		CountDownLatch releaseOwner = new CountDownLatch(1);
		try {
			var first = executor.submit(() -> service.execute(OPERATION, "rollback-race", null, () -> {
				jdbc.update("INSERT INTO idempotency_test_effects VALUES (1)");
				ownerEntered.countDown();
				await(releaseOwner);
				throw new IllegalStateException("Simulated crash");
			}));
			assertTrue(ownerEntered.await(10, TimeUnit.SECONDS));
			var retry = executor.submit(() -> other.execute(OPERATION, "rollback-race", null, () -> {
				jdbc.update("INSERT INTO idempotency_test_effects VALUES (2)");
				return CREATED;
			}));
			assertThrows(TimeoutException.class, () -> retry.get(200, TimeUnit.MILLISECONDS));
			releaseOwner.countDown();
			assertThrows(ExecutionException.class, () -> first.get(10, TimeUnit.SECONDS));
			assertEquals(CREATED, retry.get(10, TimeUnit.SECONDS));
			assertEquals(List.of(2), jdbc.queryForList("SELECT id FROM idempotency_test_effects", Integer.class));
			assertEquals(1, recordCount());
		}
		finally {
			releaseOwner.countDown();
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
		}
	}

	@Test
	void simultaneousCompletedRetriesUseCompatibleSharedLocks() throws Exception {
		service.execute(OPERATION, "completed-race", null, () -> CREATED);
		CountDownLatch duplicateLocksAcquired = new CountDownLatch(2);
		CountDownLatch releaseReads = new CountDownLatch(1);
		AtomicInteger replayFailures = new AtomicInteger();
		IdempotencyRepository synchronizedRepository = new IdempotencyRepository(jdbc) {
			@Override
			public boolean reserve(String operation, String key, String hash) {
				boolean owner = super.reserve(operation, key, hash);
				if (!owner) {
					duplicateLocksAcquired.countDown();
					await(releaseReads);
				}
				return owner;
			}

			@Override
			public Optional<StoredRecord> find(String operation, String key) {
				try {
					return super.find(operation, key);
				}
				catch (DataAccessException exception) {
					replayFailures.incrementAndGet();
					throw exception;
				}
			}
		};
		IdempotencyService firstInstance = instanceWithRepository(synchronizedRepository);
		IdempotencyService secondInstance = instanceWithRepository(synchronizedRepository);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			var first = executor.submit(() -> firstInstance.execute(OPERATION, "completed-race", null, () -> {
				throw new AssertionError("Completed request must be replayed");
			}));
			var second = executor.submit(() -> secondInstance.execute(OPERATION, "completed-race", null, () -> {
				throw new AssertionError("Completed request must be replayed");
			}));
			assertTrue(duplicateLocksAcquired.await(10, TimeUnit.SECONDS),
					"Both retries must hold INSERT IGNORE shared locks before reading");
			releaseReads.countDown();
			assertEquals(CREATED, first.get(10, TimeUnit.SECONDS));
			assertEquals(CREATED, second.get(10, TimeUnit.SECONDS));
			assertEquals(0, replayFailures.get(), "Completed replay must not rely on deadlock recovery");
			assertEquals(1, recordCount());
		}
		finally {
			releaseReads.countDown();
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
		}
	}

	@Test
	void multipleWaitingRetriesRecoverAfterOwnerRollbackAndDeadlock() throws Exception {
		AtomicInteger deadlocks = new AtomicInteger();
		AtomicInteger committedCallbacks = new AtomicInteger();
		IdempotencyRepository trackingRepository = new IdempotencyRepository(jdbc) {
			@Override
			public boolean reserve(String operation, String key, String hash) {
				try {
					return super.reserve(operation, key, hash);
				}
				catch (DataAccessException exception) {
					if (exception.getMostSpecificCause() instanceof SQLException sql
							&& sql.getErrorCode() == 1213) {
						deadlocks.incrementAndGet();
					}
					throw exception;
				}
			}
		};
		IdempotencyService firstInstance = instanceWithRepository(trackingRepository);
		IdempotencyService secondInstance = instanceWithRepository(trackingRepository);
		ExecutorService executor = Executors.newFixedThreadPool(3);
		CountDownLatch ownerEntered = new CountDownLatch(1);
		CountDownLatch releaseOwner = new CountDownLatch(1);
		try {
			var owner = executor.submit(() -> service.execute(OPERATION, "many-waiters", null, () -> {
				jdbc.update("INSERT INTO idempotency_test_effects VALUES (1)");
				ownerEntered.countDown();
				await(releaseOwner);
				throw new IllegalStateException("Simulated owner crash");
			}));
			assertTrue(ownerEntered.await(10, TimeUnit.SECONDS));
			java.util.function.Supplier<StoredHttpResponse> callback = () -> {
				committedCallbacks.incrementAndGet();
				jdbc.update("INSERT INTO idempotency_test_effects VALUES (2)");
				return CREATED;
			};
			var first = executor.submit(() -> firstInstance.execute(OPERATION, "many-waiters", null, callback));
			var second = executor.submit(() -> secondInstance.execute(OPERATION, "many-waiters", null, callback));
			awaitWaitingReservations(2);
			releaseOwner.countDown();

			assertThrows(ExecutionException.class, () -> owner.get(10, TimeUnit.SECONDS));
			assertEquals(CREATED, first.get(10, TimeUnit.SECONDS));
			assertEquals(CREATED, second.get(10, TimeUnit.SECONDS));
			assertTrue(deadlocks.get() > 0, "The test must exercise an actual MySQL reservation deadlock");
			assertEquals(1, committedCallbacks.get());
			assertEquals(List.of(2), jdbc.queryForList("SELECT id FROM idempotency_test_effects", Integer.class));
			assertEquals(1, recordCount());
		}
		finally {
			releaseOwner.countDown();
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
		}
	}

	@Test
	void invalidKeyIsRejectedBeforeAnyReservation() {
		ApiException exception = assertThrows(ApiException.class,
				() -> service.execute(OPERATION, " ", null, () -> CREATED));
		assertEquals(ApiErrorCode.VALIDATION_FAILED, exception.errorCode());
		assertEquals(0, recordCount());
	}

	private IdempotencyService secondInstance() {
		return context.getAutowireCapableBeanFactory().createBean(IdempotencyService.class);
	}

	private IdempotencyService instanceWithRepository(IdempotencyRepository repository) {
		IdempotencyService instance = new IdempotencyService(repository, context.getBean(RequestHasher.class),
				mapper, jdbc, context.getBean(PlatformTransactionManager.class));
		context.getAutowireCapableBeanFactory().autowireBean(instance);
		return instance;
	}

	private void awaitWaitingReservations(int expected) throws InterruptedException {
		JdbcTemplate observer = new JdbcTemplate(
				new DriverManagerDataSource(mysql.getJdbcUrl(), "root", mysql.getPassword()));
		long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		while (System.nanoTime() < deadline) {
			Integer waiting = observer.queryForObject("""
					SELECT COUNT(DISTINCT waits.REQUESTING_ENGINE_TRANSACTION_ID)
					FROM performance_schema.data_lock_waits waits
					JOIN performance_schema.data_locks locks
					    ON waits.REQUESTING_ENGINE_LOCK_ID = locks.ENGINE_LOCK_ID
					WHERE locks.OBJECT_SCHEMA = ? AND locks.OBJECT_NAME = 'idempotency_records'
					""", Integer.class, mysql.getDatabaseName());
			if (waiting != null && waiting >= expected) {
				return;
			}
			Thread.sleep(10);
		}
		throw new AssertionError("Expected " + expected + " reservations waiting on actual MySQL locks");
	}

	private int recordCount() {
		return jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records", Integer.class);
	}

	private int effectCount() {
		return jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_test_effects", Integer.class);
	}

	private static void await(CountDownLatch latch) {
		try {
			assertTrue(latch.await(10, TimeUnit.SECONDS), "Timed out waiting for owner release");
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted waiting for owner release", exception);
		}
	}
}
