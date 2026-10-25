package ro.demo.ledger_api.shared.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import ro.demo.ledger_api.messaging.outbox.OutboxContext;
import ro.demo.ledger_api.messaging.payment.PaymentSettledMessage;
import ro.demo.ledger_api.shared.domain.CurrencyCode;

class MessagingCorrelationInterceptorTests {
	private final CorrelationContext correlations = new CorrelationContext();
	private final OutboxContext outbox = new OutboxContext();
	private final MessagingCorrelationInterceptor interceptor =
			new MessagingCorrelationInterceptor(correlations, outbox);

	@Test
	void usesAmqpCorrelationAndScopesPaymentMdcAndOutboxCausation() {
		UUID eventId = UUID.randomUUID();
		PaymentSettledMessage payment = new PaymentSettledMessage(
				eventId, "payment-42", UUID.randomUUID(), new BigDecimal("1.00"), CurrencyCode.EUR);
		MessageProperties properties = new MessageProperties();
		properties.setCorrelationId("upstream-correlation");

		interceptor.processPayment(payment, new Message(new byte[0], properties), () -> {
			assertEquals("upstream-correlation", correlations.currentId());
			assertEquals("upstream-correlation", MDC.get("correlationId"));
			assertEquals(eventId.toString(), MDC.get("eventId"));
			assertEquals("payment-42", MDC.get("paymentId"));
			OutboxContext.Values values = outbox.currentOr("fallback");
			assertEquals("upstream-correlation", values.correlationId());
			assertEquals(eventId.toString(), values.causationId());
		});

		assertNull(correlations.currentId());
		assertNull(MDC.get("correlationId"));
		assertNull(MDC.get("eventId"));
		assertNull(MDC.get("paymentId"));
	}

	@Test
	void fallsBackToEventIdWhenAmqpCorrelationIsAbsent() {
		UUID eventId = UUID.randomUUID();
		PaymentSettledMessage payment = new PaymentSettledMessage(
				eventId, "payment-43", UUID.randomUUID(), BigDecimal.ONE, CurrencyCode.EUR);
		interceptor.processPayment(payment, new Message(new byte[0], new MessageProperties()),
				() -> assertEquals(eventId.toString(), correlations.currentId()));
	}
}
