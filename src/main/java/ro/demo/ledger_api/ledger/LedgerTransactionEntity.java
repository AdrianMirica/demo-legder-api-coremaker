package ro.demo.ledger_api.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ro.demo.ledger_api.shared.domain.CurrencyCode;

@Entity
@Table(name = "ledger_transactions")
public class LedgerTransactionEntity {
	@Id
	@Column(name = "id", length = 36, nullable = false, updatable = false)
	private String id;

	@Column(name = "ledger_sequence", insertable = false, updatable = false)
	private Long ledgerSequence;

	@Column(name = "description", length = 255, nullable = false, updatable = false)
	private String description;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "currency", length = 3, nullable = false, updatable = false)
	private CurrencyCode currency;

	@Enumerated(EnumType.STRING)
	@Column(name = "source", length = 16, nullable = false, updatable = false)
	private TransactionSource source;

	@Column(name = "source_reference", length = 128, updatable = false)
	private String sourceReference;

	@Column(name = "reversed_transaction_id", length = 36, updatable = false)
	private String reversedTransactionId;

	@Column(name = "recorded_at", nullable = false, updatable = false)
	private Instant recordedAt;

	protected LedgerTransactionEntity() {
	}

	LedgerTransactionEntity(String id, String description, CurrencyCode currency,
			TransactionSource source, String sourceReference, String reversedTransactionId,
			Instant recordedAt) {
		this.id = Objects.requireNonNull(id, "id");
		this.description = Objects.requireNonNull(description, "description");
		this.currency = Objects.requireNonNull(currency, "currency");
		this.source = Objects.requireNonNull(source, "source");
		this.sourceReference = sourceReference;
		this.reversedTransactionId = reversedTransactionId;
		this.recordedAt = Objects.requireNonNull(recordedAt, "recordedAt");
	}

	public String id() {
		return id;
	}

	public String description() {
		return description;
	}

	public CurrencyCode currency() {
		return currency;
	}

	public TransactionSource source() {
		return source;
	}

	public String reversedTransactionId() {
		return reversedTransactionId;
	}

	public Instant recordedAt() {
		return recordedAt;
	}
}
