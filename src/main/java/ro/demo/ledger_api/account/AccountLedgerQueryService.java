package ro.demo.ledger_api.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ro.demo.ledger_api.shared.api.ApiErrorCode;
import ro.demo.ledger_api.shared.api.ApiException;
import ro.demo.ledger_api.shared.api.ApiViolation;
import ro.demo.ledger_api.shared.domain.CurrencyCode;
import ro.demo.ledger_api.shared.domain.EntryDirection;

@Service
public class AccountLedgerQueryService {
	private static final DateTimeFormatter MYSQL_UTC_MICROSECONDS =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS")
					.withZone(ZoneOffset.UTC);

	private final AccountRepository accounts;
	private final JdbcTemplate jdbc;

	public AccountLedgerQueryService(AccountRepository accounts, JdbcTemplate jdbc) {
		this.accounts = accounts;
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public AccountEntryPageResponse entries(UUID accountId, int page, int size) {
		AccountEntity account = findAccount(accountId);
		long totalElements = jdbc.queryForObject(
				"SELECT COUNT(*) FROM ledger_entries WHERE account_id = ?",
				Long.class, account.id());
		long offset = Math.multiplyExact((long) page, size);
		List<AccountEntryResponse> content = jdbc.query("""
				SELECT e.id, e.sequence_no, e.account_id, e.direction, e.amount, e.balance_after,
				       t.id AS transaction_id, t.description, t.recorded_at
				FROM ledger_entries e
				JOIN ledger_transactions t ON t.id = e.transaction_id
				WHERE e.account_id = ?
				ORDER BY t.recorded_at DESC, t.ledger_sequence DESC, e.sequence_no DESC
				LIMIT ? OFFSET ?
				""", (row, index) -> new AccountEntryResponse(
						UUID.fromString(row.getString("id")),
						row.getInt("sequence_no"),
						UUID.fromString(row.getString("account_id")),
						EntryDirection.valueOf(row.getString("direction")),
						normalize(row.getBigDecimal("amount"), account.currency()),
						normalize(row.getBigDecimal("balance_after"), account.currency()),
						UUID.fromString(row.getString("transaction_id")),
						row.getString("description"),
						row.getObject("recorded_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)),
				account.id(), size, offset);
		return new AccountEntryPageResponse(
				content,
				page,
				size,
				totalElements,
				totalPages(totalElements, size));
	}

	@Transactional(readOnly = true)
	public HistoricalBalanceResponse historicalBalance(UUID accountId, Instant at) {
		if (at.isAfter(Instant.now())) {
			throw new ApiException(ApiErrorCode.VALIDATION_FAILED,
					"Historical balance instant must not be in the future",
					List.of(new ApiViolation("at", "PAST_OR_PRESENT",
							"Must not be in the future")));
		}
		AccountEntity account = findAccount(accountId);
		BigDecimal balance = jdbc.query("""
				SELECT e.balance_after
				FROM ledger_entries e
				JOIN ledger_transactions t ON t.id = e.transaction_id
				WHERE e.account_id = ?
				  AND t.recorded_at <= CAST(? AS DATETIME(6))
				ORDER BY t.recorded_at DESC, t.ledger_sequence DESC, e.sequence_no DESC
				LIMIT 1
				""", result -> result.next() ? result.getBigDecimal("balance_after") : null,
				account.id(), MYSQL_UTC_MICROSECONDS.format(at));
		if (balance == null) {
			balance = BigDecimal.ZERO.setScale(account.currency().scale());
		}
		else {
			balance = normalize(balance, account.currency());
		}
		return new HistoricalBalanceResponse(
				accountId,
				account.currency(),
				balance,
				at);
	}

	private AccountEntity findAccount(UUID accountId) {
		return accounts.findById(accountId.toString())
				.orElseThrow(() -> new ApiException(ApiErrorCode.ACCOUNT_NOT_FOUND,
						"Account " + accountId + " was not found"));
	}

	static long totalPages(long totalElements, int size) {
		return totalElements / size + (totalElements % size == 0 ? 0 : 1);
	}

	private BigDecimal normalize(BigDecimal amount, CurrencyCode currency) {
		return amount.setScale(currency.scale());
	}
}
