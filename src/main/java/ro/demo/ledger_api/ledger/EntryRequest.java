package ro.demo.ledger_api.ledger;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import ro.demo.ledger_api.shared.domain.EntryDirection;

public record EntryRequest(
		@NotNull UUID accountId,
		@NotNull EntryDirection direction,
		@NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount) {
}
