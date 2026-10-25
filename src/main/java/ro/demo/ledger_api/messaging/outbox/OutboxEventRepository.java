package ro.demo.ledger_api.messaging.outbox;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxEventRepository {
	private final JdbcTemplate jdbc;

	public OutboxEventRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public void insert(OutboxRecord event) {
		jdbc.update("""
				INSERT INTO outbox_events
				    (id, aggregate_id, event_type, routing_key, payload, correlation_id, created_at)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				""", event.id(), event.aggregateId(), event.eventType(), event.routingKey(),
				event.payload(), event.correlationId(),
				LocalDateTime.ofInstant(event.createdAt(), ZoneOffset.UTC));
	}

	public List<OutboxRecord> lockBatch(int batchSize) {
		return jdbc.query("""
				SELECT id, aggregate_id, event_type, routing_key, payload, correlation_id, created_at
				FROM outbox_events
				WHERE published_at IS NULL
				ORDER BY created_at, id
				LIMIT ?
				FOR UPDATE SKIP LOCKED
				""", (row, index) -> new OutboxRecord(
						row.getString("id"),
						row.getString("aggregate_id"),
						row.getString("event_type"),
						row.getString("routing_key"),
						row.getString("payload"),
						row.getString("correlation_id"),
						row.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)),
				batchSize);
	}

	public void markPublished(String id) {
		if (jdbc.update("""
				UPDATE outbox_events
				SET published_at = UTC_TIMESTAMP(6), last_error = NULL
				WHERE id = ? AND published_at IS NULL
				""", id) != 1) {
			throw new IllegalStateException("Expected one unpublished outbox event");
		}
	}

	public void markFailed(String id, Throwable failure) {
		String message = failure.getMessage() == null ? failure.getClass().getSimpleName()
				: failure.getClass().getSimpleName() + ": " + failure.getMessage();
		if (message.length() > 1000) {
			message = message.substring(0, 1000);
		}
		jdbc.update("""
				UPDATE outbox_events
				SET attempts = attempts + 1, last_error = ?
				WHERE id = ? AND published_at IS NULL
				""", message, id);
	}

	public record OutboxRecord(
			String id,
			String aggregateId,
			String eventType,
			String routingKey,
			String payload,
			String correlationId,
			java.time.Instant createdAt) {
	}
}
