package ro.demo.ledger_api.messaging.outbox;

import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import ro.demo.ledger_api.shared.api.ApiErrorCode;
import ro.demo.ledger_api.shared.api.ApiException;

@Component
public class OutboxContext {
	private final ThreadLocal<Values> current = new ThreadLocal<>();

	public <T> T with(String correlationId, String causationId, Supplier<T> action) {
		Objects.requireNonNull(action, "action");
		Values previous = current.get();
		current.set(new Values(validate(correlationId, "correlationId"), causationId));
		try {
			return action.get();
		}
		finally {
			if (previous == null) {
				current.remove();
			}
			else {
				current.set(previous);
			}
		}
	}

	public void with(String correlationId, String causationId, Runnable action) {
		with(correlationId, causationId, () -> {
			action.run();
			return null;
		});
	}

	public Values currentOr(String fallbackCorrelationId) {
		Values values = current.get();
		return values == null ? new Values(validate(fallbackCorrelationId, "fallbackCorrelationId"), null)
				: values;
	}

	private String validate(String value, String field) {
		if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > 128) {
			throw new ApiException(ApiErrorCode.VALIDATION_FAILED,
					field + " must contain 1 to 128 characters");
		}
		return value;
	}

	public record Values(String correlationId, String causationId) {
	}
}
