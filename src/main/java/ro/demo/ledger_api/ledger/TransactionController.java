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
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {
	private static final String RECORD_TRANSACTION_OPERATION = "RECORD_TRANSACTION";

	private final LedgerPostingService ledger;
	private final IdempotencyService idempotency;
	private final ObjectMapper mapper;

	public TransactionController(LedgerPostingService ledger, IdempotencyService idempotency,
			ObjectMapper mapper) {
		this.ledger = ledger;
		this.idempotency = idempotency;
		this.mapper = mapper;
	}

	@PostMapping
	public ResponseEntity<String> record(
			@RequestHeader("Idempotency-Key") String idempotencyKey,
			@Valid @RequestBody RecordTransactionRequest request) {
		StoredHttpResponse response = idempotency.execute(
				RECORD_TRANSACTION_OPERATION,
				idempotencyKey,
				request,
				() -> idempotency.jsonResponse(HttpStatus.CREATED, ledger.recordApi(request)));
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
}
