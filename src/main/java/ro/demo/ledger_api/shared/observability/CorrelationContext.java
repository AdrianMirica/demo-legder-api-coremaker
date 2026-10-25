package ro.demo.ledger_api.shared.observability;

import java.util.Map;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Component
public class CorrelationContext {
	public static final String CORRELATION_ID = "correlationId";
	private final ThreadLocal<String> current = new ThreadLocal<>();

	public Scope open(String correlationId) {
		String previous = current.get();
		Map<String, String> previousMdc = MDC.getCopyOfContextMap();
		current.set(correlationId);
		MDC.put(CORRELATION_ID, correlationId);
		return () -> {
			if (previous == null) {
				current.remove();
			}
			else {
				current.set(previous);
			}
			MDC.clear();
			if (previousMdc != null) {
				MDC.setContextMap(previousMdc);
			}
		};
	}

	public String currentId() {
		return current.get();
	}

	@FunctionalInterface
	public interface Scope extends AutoCloseable {
		@Override
		void close();
	}
}
