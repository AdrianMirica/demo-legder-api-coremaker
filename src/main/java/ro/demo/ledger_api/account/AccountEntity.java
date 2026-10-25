package ro.demo.ledger_api.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ro.demo.ledger_api.shared.domain.CurrencyCode;

@Entity
@Table(name = "accounts")
public class AccountEntity {
	@Id
	@Column(name = "id", length = 36, nullable = false, updatable = false)
	private String id;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "currency", length = 3, nullable = false, updatable = false)
	private CurrencyCode currency;

	@Column(name = "overdraft_limit", precision = 19, scale = 2)
	private BigDecimal overdraftLimit;

	@Column(name = "balance", precision = 19, scale = 2, nullable = false)
	private BigDecimal balance;

	@Column(name = "settlement_account", nullable = false, updatable = false)
	private boolean settlementAccount;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected AccountEntity() {
	}

	private AccountEntity(String id, CurrencyCode currency, BigDecimal overdraftLimit,
			boolean settlementAccount, Instant createdAt) {
		this.id = Objects.requireNonNull(id, "id");
		this.currency = Objects.requireNonNull(currency, "currency");
		this.overdraftLimit = normalize(overdraftLimit, currency);
		this.balance = BigDecimal.ZERO.setScale(currency.scale());
		this.settlementAccount = settlementAccount;
		this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
	}

	static AccountEntity open(String id, CurrencyCode currency, BigDecimal overdraftLimit, Instant createdAt) {
		return new AccountEntity(id, currency, overdraftLimit, false, createdAt);
	}

	public void applyDelta(BigDecimal delta) {
		Objects.requireNonNull(delta, "delta");
		balance = normalize(balance.add(delta), currency);
	}

	public String id() {
		return id;
	}

	public CurrencyCode currency() {
		return currency;
	}

	public BigDecimal overdraftLimit() {
		return overdraftLimit == null ? null : normalize(overdraftLimit, currency);
	}

	public BigDecimal balance() {
		return normalize(balance, currency);
	}

	public boolean settlementAccount() {
		return settlementAccount;
	}

	public Instant createdAt() {
		return createdAt;
	}

	private static BigDecimal normalize(BigDecimal amount, CurrencyCode currency) {
		return amount == null ? null : amount.setScale(currency.scale());
	}
}
