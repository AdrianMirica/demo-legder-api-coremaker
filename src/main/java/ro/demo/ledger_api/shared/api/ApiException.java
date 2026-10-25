package ro.demo.ledger_api.shared.api;

import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
	private final HttpStatus status;
	private final ApiErrorCode errorCode;
	private final List<ApiViolation> violations;

	public ApiException(ApiErrorCode errorCode, String detail) {
		this(errorCode, detail, List.of());
	}

	public ApiException(ApiErrorCode errorCode, String detail, List<ApiViolation> violations) {
		super(Objects.requireNonNull(detail, "detail"));
		this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
		this.status = errorCode.status();
		this.violations = List.copyOf(Objects.requireNonNull(violations, "violations"));
	}

	public HttpStatus status() {
		return status;
	}

	public ApiErrorCode errorCode() {
		return errorCode;
	}

	public List<ApiViolation> violations() {
		return violations;
	}
}
