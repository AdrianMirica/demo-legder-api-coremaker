package ro.demo.ledger_api.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import tools.jackson.databind.json.JsonMapper;

class IdempotencyRetryTests {
	private static final String OPERATION = "OPEN_ACCOUNT";
	private static final String KEY = "retry";
	private static final StoredHttpResponse CREATED =
			new StoredHttpResponse(201, "application/json", "{\"id\":\"one\"}");
	private final IdempotencyRepository repository = mock(IdempotencyRepository.class);
	private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
	private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
	private final RequestHasher hasher = new RequestHasher(JsonMapper.builder().build());
	private final String hash = hasher.hash(OPERATION, null);
	private final IdempotencyService service = new IdempotencyService(repository, hasher,
			JsonMapper.builder().build(), jdbc, transactions);

	@BeforeEach
	void setUp() {
		when(transactions.getTransaction(any())).thenAnswer(invocation -> mock(TransactionStatus.class));
		when(repository.reserve(OPERATION, KEY, hash)).thenReturn(true);
		when(jdbc.execute(ArgumentMatchers.<ConnectionCallback<StoredHttpResponse>>any())).thenReturn(CREATED);
	}

	@Test
	void reservationDeadlockRetriesInANewTransaction() {
		when(repository.reserve(OPERATION, KEY, hash)).thenThrow(deadlock()).thenReturn(true);

		assertEquals(CREATED, execute());
		verify(transactions, times(2)).getTransaction(any());
		verify(transactions).rollback(any());
		verify(transactions).commit(any());
		verify(repository, times(2)).reserve(OPERATION, KEY, hash);
	}

	@Test
	void deadlockAfterCallbackRetriesTheWholeTransaction() {
		doThrow(deadlock()).doNothing().when(repository).complete(OPERATION, KEY, CREATED);

		assertEquals(CREATED, execute());
		verify(repository, times(2)).reserve(OPERATION, KEY, hash);
		verify(jdbc, times(2)).execute(ArgumentMatchers.<ConnectionCallback<StoredHttpResponse>>any());
		verify(repository, times(2)).complete(OPERATION, KEY, CREATED);
		verify(transactions).rollback(any());
		verify(transactions).commit(any());
	}

	@Test
	void deadlockRetryStopsAfterThreeAttemptsAndPropagatesFailure() {
		CannotAcquireLockException failure = deadlock();
		when(repository.reserve(OPERATION, KEY, hash)).thenThrow(failure);

		assertSame(failure, assertThrows(CannotAcquireLockException.class, this::execute));
		verify(transactions, times(3)).getTransaction(any());
		verify(transactions, times(3)).rollback(any());
	}

	@Test
	void unrelatedLockFailureIsNotRetried() {
		CannotAcquireLockException failure = new CannotAcquireLockException("Lock timeout",
				new SQLException("Lock wait timeout exceeded", "HY000", 1205));
		when(repository.reserve(OPERATION, KEY, hash)).thenThrow(failure);

		assertSame(failure, assertThrows(CannotAcquireLockException.class, this::execute));
		verify(transactions).getTransaction(any());
		verify(transactions).rollback(any());
	}

	private StoredHttpResponse execute() {
		Supplier<StoredHttpResponse> callback = () -> CREATED;
		return service.execute(OPERATION, KEY, null, callback);
	}

	private CannotAcquireLockException deadlock() {
		return new CannotAcquireLockException("Deadlock",
				new SQLException("Deadlock found when trying to get lock", "40001", 1213));
	}
}
