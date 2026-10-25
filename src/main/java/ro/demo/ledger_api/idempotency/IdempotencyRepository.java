package ro.demo.ledger_api.idempotency;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyRepository {
	private final JdbcTemplate jdbc;

	public IdempotencyRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public boolean reserve(String operation, String key, String hash) {
		return jdbc.update("""
				INSERT IGNORE INTO idempotency_records
				    (operation, idempotency_key, request_hash, created_at)
				VALUES (?, ?, ?, UTC_TIMESTAMP(6))
				""", operation, key, hash) == 1;
	}

	public Optional<StoredRecord> find(String operation, String key) {
		List<StoredRecord> records = jdbc.query("""
				SELECT request_hash, http_status, content_type, response_body_text, completed_at
				FROM idempotency_records
				WHERE operation = ? AND idempotency_key = ?
				FOR SHARE
				""", (row, index) -> {
			if (row.getTimestamp("completed_at") == null) {
				throw new IllegalStateException("Committed idempotency record is incomplete");
			}
			return new StoredRecord(row.getString("request_hash"),
					new StoredHttpResponse(row.getInt("http_status"), row.getString("content_type"),
							row.getString("response_body_text")));
		}, operation, key);
		return records.stream().findFirst();
	}

	public void complete(String operation, String key, StoredHttpResponse response) {
		int updated = jdbc.update("""
				UPDATE idempotency_records
				SET http_status = ?, content_type = ?, response_body = ?, response_body_text = ?,
				    completed_at = UTC_TIMESTAMP(6)
				WHERE operation = ? AND idempotency_key = ? AND completed_at IS NULL
				""", response.status(), response.contentType(), response.body(), response.body(),
				operation, key);
		if (updated != 1) {
			throw new IllegalStateException("Expected one reserved idempotency record");
		}
	}

	public record StoredRecord(String requestHash, StoredHttpResponse response) {
	}
}
