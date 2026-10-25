package ro.demo.ledger_api.shared.api;

import org.springframework.http.HttpStatus;

public enum ApiErrorCode {
	VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
	UNSUPPORTED_CURRENCY(HttpStatus.BAD_REQUEST),
	INVALID_CURRENCY_CODE(HttpStatus.BAD_REQUEST),
	INVALID_AMOUNT_SCALE(HttpStatus.BAD_REQUEST),
	UNBALANCED_TRANSACTION(HttpStatus.BAD_REQUEST),
	CURRENCY_MISMATCH(HttpStatus.BAD_REQUEST),
	REVERSAL_NOT_ALLOWED(HttpStatus.BAD_REQUEST),
	ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND),
	TRANSACTION_NOT_FOUND(HttpStatus.NOT_FOUND),
	IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT),
	OVERDRAFT_LIMIT_EXCEEDED(HttpStatus.CONFLICT),
	TRANSACTION_ALREADY_REVERSED(HttpStatus.CONFLICT),
	PAYMENT_PAYLOAD_CONFLICT(HttpStatus.CONFLICT),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

	private final HttpStatus status;

	ApiErrorCode(HttpStatus status) {
		this.status = status;
	}

	public HttpStatus status() {
		return status;
	}
}
