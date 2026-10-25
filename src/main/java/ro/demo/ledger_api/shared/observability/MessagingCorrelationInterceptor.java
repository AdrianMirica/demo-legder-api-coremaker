package ro.demo.ledger_api.shared.observability;

import java.util.Map;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;
import ro.demo.ledger_api.messaging.outbox.OutboxContext;
import ro.demo.ledger_api.messaging.payment.PaymentSettledMessage;

@Component
public class MessagingCorrelationInterceptor {
	private final CorrelationContext correlations;
	private final OutboxContext outbox;

	public MessagingCorrelationInterceptor(CorrelationContext correlations, OutboxContext outbox) {
		this.correlations = correlations;
		this.outbox = outbox;
	}

	public void processPayment(PaymentSettledMessage payment, Message rawMessage, Runnable action) {
		String incoming = rawMessage.getMessageProperties().getCorrelationId();
		String correlationId = incoming == null || incoming.isBlank()
				? payment.eventId().toString() : incoming;
		Map<String, String> previous = MDC.getCopyOfContextMap();
		try (CorrelationContext.Scope ignored = correlations.open(correlationId)) {
			MDC.put("eventId", payment.eventId().toString());
			MDC.put("paymentId", payment.paymentId());
			outbox.with(correlationId, payment.eventId().toString(), action);
		}
		finally {
			MDC.clear();
			if (previous != null) {
				MDC.setContextMap(previous);
			}
		}
	}
}
