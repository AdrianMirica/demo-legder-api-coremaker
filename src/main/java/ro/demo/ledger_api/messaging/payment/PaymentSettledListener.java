package ro.demo.ledger_api.messaging.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import ro.demo.ledger_api.messaging.outbox.OutboxContext;

@Component
public class PaymentSettledListener {
	private static final Logger logger = LoggerFactory.getLogger(PaymentSettledListener.class);
	private final PaymentProcessingService payments;
	private final OutboxContext outboxContext;

	public PaymentSettledListener(PaymentProcessingService payments, OutboxContext outboxContext) {
		this.payments = payments;
		this.outboxContext = outboxContext;
	}

	@RabbitListener(id = "paymentSettledListener",
			queues = PaymentMessagingConfiguration.PAYMENT_QUEUE,
			containerFactory = "paymentListenerContainerFactory")
	public void receive(PaymentSettledMessage message) {
		String eventId = message.eventId().toString();
		outboxContext.with(eventId, eventId, () -> payments.process(message));
		logger.info("Payment processed or deduplicated: eventId={}, paymentId={}",
				message.eventId(), message.paymentId());
	}
}
