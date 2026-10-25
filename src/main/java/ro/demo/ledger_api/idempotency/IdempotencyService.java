package ro.demo.ledger_api.idempotency;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Savepoint;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import ro.demo.ledger_api.shared.api.ApiErrorCode;
import ro.demo.ledger_api.shared.api.ApiException;
import ro.demo.ledger_api.shared.api.ApiProblems;
import tools.jackson.databind.ObjectMapper;

@Service
public class IdempotencyService {
	private static final Logger logger = LoggerFactory.getLogger(IdempotencyService.class);
	private static final int MAX_TRANSACTION_ATTEMPTS = 3;

	private final IdempotencyRepository repository;
	private final RequestHasher hasher;
	private final ObjectMapper mapper;
	private final JdbcTemplate jdbc;
	private final TransactionTemplate transaction;

	@PersistenceContext
	private EntityManager entityManager;

	public IdempotencyService(IdempotencyRepository repository, RequestHasher hasher,
			ObjectMapper mapper, JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
		this.repository = repository;
		this.hasher = hasher;
		this.mapper = mapper;
		this.jdbc = jdbc;
		this.transaction = new TransactionTemplate(transactionManager);
		this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	public StoredHttpResponse execute(String operation, String key, Object request,
			Supplier<StoredHttpResponse> callback) {
		validateIdentifier(operation, "operation");
		validateIdentifier(key, "Idempotency-Key");
		Objects.requireNonNull(callback, "callback");
		String hash = hasher.hash(operation, request);
		for (int attempt = 1; ; attempt++) {
			try {
				return executeAttempt(operation, key, hash, callback);
			}
			catch (DataAccessException | TransactionException | PersistenceException exception) {
				if (!isDeadlock(exception) || attempt == MAX_TRANSACTION_ATTEMPTS) {
					throw exception;
				}
				logger.warn("Retrying idempotency transaction after MySQL deadlock: operation={}, attempt={}",
						operation, attempt);
				try {
					Thread.sleep(ThreadLocalRandom.current().nextLong(10, 25L * attempt + 1));
				}
				catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException("Interrupted while retrying idempotency transaction",
							interrupted);
				}
			}
		}
	}

	private StoredHttpResponse executeAttempt(String operation, String key, String hash,
			Supplier<StoredHttpResponse> callback) {
		return Objects.requireNonNull(transaction.execute(status -> {
			boolean owner = repository.reserve(operation, key, hash);
			if (!owner) {
				var existing = repository.find(operation, key);
				if (existing.isPresent()) {
					if (!MessageDigest.isEqual(hash.getBytes(StandardCharsets.US_ASCII),
							existing.get().requestHash().getBytes(StandardCharsets.US_ASCII))) {
						throw new ApiException(ApiErrorCode.IDEMPOTENCY_KEY_REUSED,
								"Idempotency-Key was already used for a different request");
					}
					return existing.get().response();
				}
				if (!repository.reserve(operation, key, hash)) {
					throw new IllegalStateException("Could not reserve idempotency key after retry");
				}
			}
			StoredHttpResponse response = executeCallback(callback);
			repository.complete(operation, key, response);
			return response;
		}));
	}

	private boolean isDeadlock(Throwable exception) {
		for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
			if (cause instanceof SQLException sqlException
					&& sqlException.getErrorCode() == 1213
					&& "40001".equals(sqlException.getSQLState())) {
				return true;
			}
		}
		return false;
	}

	public StoredHttpResponse jsonResponse(HttpStatus status, Object body) {
		return new StoredHttpResponse(status.value(), MediaType.APPLICATION_JSON_VALUE,
				mapper.writeValueAsString(body));
	}

	private StoredHttpResponse executeCallback(Supplier<StoredHttpResponse> callback) {
		return Objects.requireNonNull(jdbc.execute((ConnectionCallback<StoredHttpResponse>) connection -> {
			Savepoint savepoint = connection.setSavepoint();
			StoredHttpResponse response;
			try {
				response = Objects.requireNonNull(callback.get(), "callback response");
				entityManager.flush();
			}
			catch (ApiException exception) {
				connection.rollback(savepoint);
				// JDBC savepoints do not restore Hibernate's managed state.
				entityManager.clear();
				response = new StoredHttpResponse(exception.status().value(),
						MediaType.APPLICATION_PROBLEM_JSON_VALUE,
						mapper.writeValueAsString(ApiProblems.from(exception)));
			}
			connection.releaseSavepoint(savepoint);
			return response;
		}));
	}

	private void validateIdentifier(String value, String field) {
		if (value == null || value.isBlank() || value.length() > 128) {
			throw new ApiException(ApiErrorCode.VALIDATION_FAILED,
					field + " must contain between 1 and 128 characters");
		}
	}
}
