package ro.demo.ledger_api.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import ro.demo.ledger_api.shared.domain.EntryDirection;

public record AccountEntryResponse(
		UUID id,
		int sequence,
		UUID accountId,
		EntryDirection direction,
		BigDecimal amount,
		BigDecimal balanceAfter,
		UUID transactionId,
		String transactionDescription,
		Instant recordedAt) {
}
