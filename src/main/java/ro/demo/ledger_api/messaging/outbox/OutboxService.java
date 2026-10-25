package ro.demo.ledger_api.messaging.outbox;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import ro.demo.ledger_api.ledger.TransactionResponse;
import ro.demo.ledger_api.messaging.outbox.OutboxEventRepository.OutboxRecord;
import ro.demo.ledger_api.shared.domain.CurrencyCode;
import tools.jackson.databind.ObjectMapper;

@Service
public class OutboxService {
	public static final String EVENT_TYPE = "transaction-recorded";
	public static final String ROUTING_KEY = "transaction.recorded.v1";
	private final OutboxEventRepository repository;
	private final TransactionRecordedEventFactory events;
	private final OutboxContext context;
	private final ObjectMapper mapper;

	public OutboxService(OutboxEventRepository repository, TransactionRecordedEventFactory events,
			OutboxContext context, ObjectMapper mapper) {
		this.repository = repository;
		this.events = events;
		this.context = context;
		this.mapper = mapper;
	}

	public void enqueue(TransactionResponse transaction, CurrencyCode currency) {
		UUID eventId = UUID.randomUUID();
		OutboxContext.Values values = context.currentOr(transaction.id().toString());
		var event = events.create(eventId, transaction, currency,
				values.correlationId(), values.causationId());
		repository.insert(new OutboxRecord(
				eventId.toString(),
				transaction.id().toString(),
				EVENT_TYPE,
				ROUTING_KEY,
				mapper.writeValueAsString(event),
				values.correlationId(),
				Instant.now()));
	}
}
