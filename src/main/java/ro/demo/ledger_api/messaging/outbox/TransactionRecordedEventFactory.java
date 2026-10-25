package ro.demo.ledger_api.messaging.outbox;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ro.demo.ledger_api.ledger.TransactionResponse;
import ro.demo.ledger_api.shared.domain.CurrencyCode;

@Component
public class TransactionRecordedEventFactory {
	public TransactionRecordedEvent create(UUID eventId, TransactionResponse transaction,
			CurrencyCode currency, String correlationId, String causationId) {
		List<EntryPayload> entries = transaction.entries().stream()
				.map(entry -> new EntryPayload(entry.accountId(), entry.direction().name(),
						entry.amount(), entry.balanceAfter()))
				.toList();
		return new TransactionRecordedEvent(
				eventId,
				"transaction-recorded",
				1,
				Instant.now(),
				correlationId,
				causationId,
				new TransactionPayload(transaction.id(), transaction.description(),
						transaction.source().name(), transaction.recordedAt(), currency,
						transaction.reversedTransactionId(), entries));
	}

	public record TransactionRecordedEvent(
			UUID eventId,
			String eventType,
			int eventVersion,
			Instant occurredAt,
			String correlationId,
			@JsonInclude(JsonInclude.Include.ALWAYS)
			String causationId,
			TransactionPayload transaction) {
	}

	public record TransactionPayload(
			UUID id,
			String description,
			String source,
			Instant recordedAt,
			CurrencyCode currency,
			@JsonInclude(JsonInclude.Include.ALWAYS)
			UUID reversedTransactionId,
			List<EntryPayload> entries) {
	}

	public record EntryPayload(
			UUID accountId,
			String direction,
			java.math.BigDecimal amount,
			java.math.BigDecimal balanceAfter) {
	}
}
