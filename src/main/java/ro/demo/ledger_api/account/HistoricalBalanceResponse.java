package ro.demo.ledger_api.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import ro.demo.ledger_api.shared.domain.CurrencyCode;

public record HistoricalBalanceResponse(
		UUID accountId,
		CurrencyCode currency,
		BigDecimal balance,
		Instant asOf) {
}
