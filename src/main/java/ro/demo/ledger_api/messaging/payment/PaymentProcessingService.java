package ro.demo.ledger_api.messaging.payment;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import ro.demo.ledger_api.ledger.EntryRequest;
import ro.demo.ledger_api.ledger.LedgerPostingService;
import ro.demo.ledger_api.ledger.RecordTransactionRequest;
import ro.demo.ledger_api.ledger.TransactionSource;
import ro.demo.ledger_api.shared.api.ApiErrorCode;
import ro.demo.ledger_api.shared.api.ApiException;
import ro.demo.ledger_api.shared.domain.EntryDirection;

@Service
public class PaymentProcessingService {
	private static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999999999999.99");
	private final ProcessedPaymentRepository payments;
	private final PaymentPayloadHasher hasher;
	private final LedgerPostingService posting;
	private final JdbcTemplate jdbc;
	private final EntityManager entityManager;
	private final TransactionTemplate transaction;

	public PaymentProcessingService(ProcessedPaymentRepository payments, PaymentPayloadHasher hasher,
			LedgerPostingService posting, JdbcTemplate jdbc, EntityManager entityManager,
			PlatformTransactionManager transactionManager) {
		this.payments = payments;
		this.hasher = hasher;
		this.posting = posting;
		this.jdbc = jdbc;
		this.entityManager = entityManager;
		this.transaction = new TransactionTemplate(transactionManager);
		this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	public void process(PaymentSettledMessage message) {
		validate(message);
		String hash = hasher.hash(message);
		transaction.executeWithoutResult(status -> {
			if (!payments.reserve(message, hash)) {
				String existing = payments.completedHash(message.paymentId());
				if (!MessageDigest.isEqual(hash.getBytes(StandardCharsets.US_ASCII),
						existing.getBytes(StandardCharsets.US_ASCII))) {
					throw new ApiException(ApiErrorCode.PAYMENT_PAYLOAD_CONFLICT,
							"Payment ID was already processed with different data");
				}
				return;
			}

			// Read scalar metadata, not managed entities: the posting query must load fresh locked balances.
			List<String> merchantCurrencies = jdbc.queryForList(
					"SELECT currency FROM accounts WHERE id = ?", String.class,
					message.merchantAccountId().toString());
			if (merchantCurrencies.isEmpty()) {
				throw new ApiException(ApiErrorCode.ACCOUNT_NOT_FOUND, "Merchant account was not found");
			}
			if (!message.currency().name().equals(merchantCurrencies.get(0))) {
				throw new ApiException(ApiErrorCode.CURRENCY_MISMATCH,
						"Payment currency differs from merchant account currency");
			}
			String settlementId = Objects.requireNonNull(jdbc.queryForObject("""
					SELECT id FROM accounts WHERE settlement_account = TRUE AND currency = ?
					""", String.class, message.currency().name()));
			var recorded = posting.record(
					new RecordTransactionRequest("Payment settled: " + message.paymentId(), List.of(
							new EntryRequest(UUID.fromString(settlementId), EntryDirection.DEBIT,
									message.amount()),
							new EntryRequest(message.merchantAccountId(), EntryDirection.CREDIT,
									message.amount()))),
					TransactionSource.PAYMENT, message.paymentId(), null);
			entityManager.flush();
			payments.complete(message.paymentId(), recorded.id().toString());
		});
	}

	private void validate(PaymentSettledMessage message) {
		if (message == null || message.eventId() == null || message.merchantAccountId() == null
				|| message.paymentId() == null || message.paymentId().isBlank()
				|| message.paymentId().codePointCount(0, message.paymentId().length()) > 128
				|| message.currency() == null
				|| message.amount() == null || message.amount().signum() <= 0
				|| message.amount().compareTo(MAX_AMOUNT) > 0) {
			throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "Invalid payment-settled message");
		}
		if (message.amount().stripTrailingZeros().scale() > message.currency().scale()) {
			throw new ApiException(ApiErrorCode.INVALID_AMOUNT_SCALE,
					"Payment amount exceeds currency decimal places");
		}
	}
}
