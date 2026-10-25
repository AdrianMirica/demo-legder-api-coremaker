package ro.demo.ledger_api.messaging.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "outbox_events")
public class OutboxEventEntity {
	@Id
	@Column(name = "id", length = 36)
	private String id;
	@Column(name = "aggregate_id", length = 36)
	private String aggregateId;
	@Column(name = "event_type", length = 64)
	private String eventType;
	@Column(name = "routing_key", length = 128)
	private String routingKey;
	@Column(name = "payload", columnDefinition = "json")
	@JdbcTypeCode(SqlTypes.JSON)
	private String payload;
	@Column(name = "correlation_id", length = 128)
	private String correlationId;
	@Column(name = "created_at")
	private Instant createdAt;
	@Column(name = "published_at")
	private Instant publishedAt;
	@Column(name = "attempts")
	private int attempts;
	@Column(name = "last_error", length = 1000)
	private String lastError;

	protected OutboxEventEntity() {
	}
}
