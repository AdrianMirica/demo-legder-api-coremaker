package ro.demo.ledger_api.ledger;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TransactionResponse(
		UUID id,
		String description,
		TransactionSource source,
		Instant recordedAt,
		@JsonInclude(JsonInclude.Include.ALWAYS) UUID reversedTransactionId,
		List<EntryResponse> entries) {
}
