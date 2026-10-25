package ro.demo.ledger_api.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Objects;
import ro.demo.ledger_api.shared.domain.EntryDirection;

@Entity
@Table(name = "ledger_entries")
public class LedgerEntryEntity {
	@Id
	@Column(name = "id", length = 36, nullable = false, updatable = false)
	private String id;

	@Column(name = "transaction_id", length = 36, nullable = false, updatable = false)
	private String transactionId;

	@Column(name = "sequence_no", nullable = false, updatable = false)
	private int sequenceNo;

	@Column(name = "account_id", length = 36, nullable = false, updatable = false)
	private String accountId;

	@Enumerated(EnumType.STRING)
	@Column(name = "direction", length = 6, nullable = false, updatable = false)
	private EntryDirection direction;

	@Column(name = "amount", precision = 19, scale = 2, nullable = false, updatable = false)
	private BigDecimal amount;

	@Column(name = "balance_after", precision = 19, scale = 2, nullable = false, updatable = false)
	private BigDecimal balanceAfter;

	protected LedgerEntryEntity() {
	}

	LedgerEntryEntity(String id, String transactionId, int sequenceNo, String accountId,
			EntryDirection direction, BigDecimal amount, BigDecimal balanceAfter) {
		this.id = Objects.requireNonNull(id, "id");
		this.transactionId = Objects.requireNonNull(transactionId, "transactionId");
		this.sequenceNo = sequenceNo;
		this.accountId = Objects.requireNonNull(accountId, "accountId");
		this.direction = Objects.requireNonNull(direction, "direction");
		this.amount = Objects.requireNonNull(amount, "amount");
		this.balanceAfter = Objects.requireNonNull(balanceAfter, "balanceAfter");
	}

	public String id() {
		return id;
	}

	public int sequenceNo() {
		return sequenceNo;
	}

	public String accountId() {
		return accountId;
	}

	public EntryDirection direction() {
		return direction;
	}

	public BigDecimal amount() {
		return amount;
	}

	public BigDecimal balanceAfter() {
		return balanceAfter;
	}
}
