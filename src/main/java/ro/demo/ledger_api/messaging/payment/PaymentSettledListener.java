package ro.demo.ledger_api.messaging.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentSettledListener {
	private static final Logger logger = LoggerFactory.getLogger(PaymentSettledListener.class);
	private final PaymentProcessingService payments;

	public PaymentSettledListener(PaymentProcessingService payments) {
		this.payments = payments;
	}

	@RabbitListener(id = "paymentSettledListener",
			queues = PaymentMessagingConfiguration.PAYMENT_QUEUE,
			containerFactory = "paymentListenerContainerFactory")
	public void receive(PaymentSettledMessage message) {
		payments.process(message);
		logger.info("Payment processed or deduplicated: eventId={}, paymentId={}",
				message.eventId(), message.paymentId());
	}
}
