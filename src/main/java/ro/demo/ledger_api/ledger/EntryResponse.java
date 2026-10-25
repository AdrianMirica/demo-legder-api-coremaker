package ro.demo.ledger_api.ledger;

import java.math.BigDecimal;
import java.util.UUID;
import ro.demo.ledger_api.shared.domain.EntryDirection;

public record EntryResponse(
		UUID id,
		int sequence,
		UUID accountId,
		EntryDirection direction,
		BigDecimal amount,
		BigDecimal balanceAfter) {
}
