package ro.demo.ledger_api.ledger;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import ro.demo.ledger_api.idempotency.IdempotencyService;
import ro.demo.ledger_api.idempotency.StoredHttpResponse;
import ro.demo.ledger_api.messaging.outbox.OutboxContext;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {
	private static final String RECORD_TRANSACTION_OPERATION = "RECORD_TRANSACTION";
	private static final ReverseTransactionRequest DEFAULT_REVERSAL_REQUEST =
			new ReverseTransactionRequest(null);

	private final LedgerPostingService ledger;
	private final ReversalService reversals;
	private final IdempotencyService idempotency;
	private final ObjectMapper mapper;
	private final OutboxContext outboxContext;

	public TransactionController(LedgerPostingService ledger, ReversalService reversals,
			IdempotencyService idempotency, ObjectMapper mapper, OutboxContext outboxContext) {
		this.ledger = ledger;
		this.reversals = reversals;
		this.idempotency = idempotency;
		this.mapper = mapper;
		this.outboxContext = outboxContext;
	}

	@PostMapping
	public ResponseEntity<String> record(
			@RequestHeader("Idempotency-Key") String idempotencyKey,
			@RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
			@Valid @RequestBody RecordTransactionRequest request) {
		String correlation = correlationId == null ? UUID.randomUUID().toString() : correlationId;
		StoredHttpResponse response = outboxContext.with(correlation, idempotencyKey,
				() -> idempotency.execute(
						RECORD_TRANSACTION_OPERATION,
						idempotencyKey,
						request,
						() -> idempotency.jsonResponse(HttpStatus.CREATED, ledger.recordApi(request))));
		ResponseEntity.BodyBuilder result = ResponseEntity.status(response.status())
				.contentType(MediaType.valueOf(response.contentType()));
		if (response.status() == HttpStatus.CREATED.value()) {
			TransactionResponse transaction = mapper.readValue(response.body(), TransactionResponse.class);
			URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
					.path("/api/v1/transactions/{transactionId}")
					.buildAndExpand(transaction.id())
					.toUri();
			result.location(location);
		}
		return result.body(response.body());
	}

	@GetMapping("/{transactionId}")
	public ResponseEntity<TransactionResponse> get(@PathVariable UUID transactionId) {
		return ResponseEntity.ok(ledger.get(transactionId));
	}

	@PostMapping("/{transactionId}/reversal")
	public ResponseEntity<String> reverse(
			@PathVariable UUID transactionId,
			@RequestHeader("Idempotency-Key") String idempotencyKey,
			@RequestHeader(value = "X-Correlation-Id", required = false) String correlationId,
			@Valid @RequestBody(required = false) ReverseTransactionRequest request) {
		ReverseTransactionRequest normalizedRequest =
				request == null ? DEFAULT_REVERSAL_REQUEST : request;
		String correlation = correlationId == null ? UUID.randomUUID().toString() : correlationId;
		StoredHttpResponse response = outboxContext.with(correlation, idempotencyKey,
				() -> idempotency.execute(
						"REVERSE_TRANSACTION:" + transactionId,
						idempotencyKey,
						normalizedRequest,
						() -> idempotency.jsonResponse(HttpStatus.CREATED,
								reversals.reverse(transactionId, normalizedRequest))));
		ResponseEntity.BodyBuilder result = ResponseEntity.status(response.status())
				.contentType(MediaType.valueOf(response.contentType()));
		if (response.status() == HttpStatus.CREATED.value()) {
			TransactionResponse reversal = mapper.readValue(response.body(), TransactionResponse.class);
			URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
					.path("/api/v1/transactions/{transactionId}")
					.buildAndExpand(reversal.id())
					.toUri();
			result.location(location);
		}
		return result.body(response.body());
	}
}
