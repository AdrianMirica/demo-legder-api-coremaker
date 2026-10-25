package ro.demo.ledger_api.messaging.payment;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProcessedPaymentRepository {
	private final JdbcTemplate jdbc;

	public ProcessedPaymentRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public boolean reserve(PaymentSettledMessage message, String hash) {
		return jdbc.update("""
				INSERT IGNORE INTO processed_payments
				    (payment_id, first_event_id, payload_hash, processed_at)
				VALUES (?, ?, ?, UTC_TIMESTAMP(6))
				""", message.paymentId(), message.eventId().toString(), hash) == 1;
	}

	public String completedHash(String paymentId) {
		List<String> hashes = jdbc.query("""
				SELECT payload_hash, transaction_id
				FROM processed_payments
				WHERE payment_id = ?
				FOR SHARE
				""", (row, index) -> {
			if (row.getString("transaction_id") == null) {
				throw new IllegalStateException("Completed payment has no ledger transaction");
			}
			return row.getString("payload_hash");
		}, paymentId);
		if (hashes.size() != 1) {
			throw new IllegalStateException("Reserved payment record was not found");
		}
		return hashes.get(0);
	}

	public void complete(String paymentId, String transactionId) {
		int updated = jdbc.update("""
				UPDATE processed_payments
				SET transaction_id = ?, processed_at = UTC_TIMESTAMP(6)
				WHERE payment_id = ? AND transaction_id IS NULL
				""", transactionId, paymentId);
		if (updated != 1) {
			throw new IllegalStateException("Expected one reserved payment record");
		}
	}
}
