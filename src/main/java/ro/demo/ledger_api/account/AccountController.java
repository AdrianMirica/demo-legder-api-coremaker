package ro.demo.ledger_api.account;

import java.net.URI;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
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
@RequestMapping("/api/v1/accounts")
public class AccountController {
	private static final String OPEN_ACCOUNT_OPERATION = "OPEN_ACCOUNT";

	private final AccountService accounts;
	private final IdempotencyService idempotency;
	private final ObjectMapper mapper;

	public AccountController(AccountService accounts, IdempotencyService idempotency, ObjectMapper mapper) {
		this.accounts = accounts;
		this.idempotency = idempotency;
		this.mapper = mapper;
	}

	@PostMapping
	public ResponseEntity<String> open(
			@RequestHeader("Idempotency-Key") String idempotencyKey,
			@RequestBody OpenAccountRequest request) {
		StoredHttpResponse response = idempotency.execute(OPEN_ACCOUNT_OPERATION, idempotencyKey, request,
				() -> idempotency.jsonResponse(HttpStatus.CREATED, accounts.open(request)));
		ResponseEntity.BodyBuilder result = ResponseEntity.status(response.status())
				.contentType(MediaType.valueOf(response.contentType()));
		if (response.status() == HttpStatus.CREATED.value()) {
			AccountResponse account = mapper.readValue(response.body(), AccountResponse.class);
			URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
					.path("/api/v1/accounts/{accountId}")
					.buildAndExpand(account.id())
					.toUri();
			result.location(location);
		}
		return result.body(response.body());
	}

	@GetMapping("/{accountId}")
	public ResponseEntity<AccountResponse> get(@PathVariable UUID accountId) {
		return ResponseEntity.ok(accounts.get(accountId));
	}
}
