package ro.demo.ledger_api.messaging.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;
import ro.demo.ledger_api.shared.observability.MessagingCorrelationInterceptor;

@Component
public class PaymentSettledListener {
	private static final Logger logger = LoggerFactory.getLogger(PaymentSettledListener.class);
	private final PaymentProcessingService payments;
	private final MessagingCorrelationInterceptor correlations;

	public PaymentSettledListener(PaymentProcessingService payments,
			MessagingCorrelationInterceptor correlations) {
		this.payments = payments;
		this.correlations = correlations;
	}

	@RabbitListener(id = "paymentSettledListener",
			queues = PaymentMessagingConfiguration.PAYMENT_QUEUE,
			containerFactory = "paymentListenerContainerFactory")
	public void receive(PaymentSettledMessage message, Message rawMessage) {
		correlations.processPayment(message, rawMessage, () -> {
			logger.atInfo().addKeyValue("eventId", message.eventId())
					.addKeyValue("paymentId", message.paymentId())
					.log("Payment message received");
			payments.process(message);
			logger.atInfo().addKeyValue("eventId", message.eventId())
					.addKeyValue("paymentId", message.paymentId())
					.log("Payment processed or deduplicated");
		});
	}
}
