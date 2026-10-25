package ro.demo.ledger_api.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;
import ro.demo.ledger_api.shared.domain.CurrencyCode;

public record AccountResponse(
		UUID id,
		CurrencyCode currency,
		@JsonInclude(JsonInclude.Include.ALWAYS)
		BigDecimal overdraftLimit,
		BigDecimal balance,
		boolean settlementAccount,
		Instant createdAt) {
}
