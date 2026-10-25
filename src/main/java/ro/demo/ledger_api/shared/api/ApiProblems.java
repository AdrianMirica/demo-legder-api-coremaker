package ro.demo.ledger_api.shared.api;

import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

public final class ApiProblems {
	private ApiProblems() {
	}

	public static ProblemDetail from(ApiException exception) {
		return create(exception.status(), exception.getMessage(), exception.errorCode(),
				exception.violations());
	}

	public static ProblemDetail create(HttpStatus status, String detail, ApiErrorCode errorCode,
			List<ApiViolation> violations) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setType(URI.create("about:blank"));
		problem.setProperty("errorCode", errorCode.name());
		if (!violations.isEmpty()) {
			problem.setProperty("violations", violations);
		}
		return problem;
	}
}
