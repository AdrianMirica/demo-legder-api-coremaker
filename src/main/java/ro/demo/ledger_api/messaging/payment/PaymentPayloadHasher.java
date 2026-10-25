package ro.demo.ledger_api.messaging.payment;

import java.util.Map;
import org.springframework.stereotype.Component;
import ro.demo.ledger_api.idempotency.RequestHasher;

@Component
public class PaymentPayloadHasher {
	private final RequestHasher hasher;

	public PaymentPayloadHasher(RequestHasher hasher) {
		this.hasher = hasher;
	}

	public String hash(PaymentSettledMessage message) {
		return hasher.hash("PAYMENT_SETTLED", Map.of(
				"paymentId", message.paymentId(),
				"merchantAccountId", message.merchantAccountId(),
				"amount", message.amount().setScale(message.currency().scale()),
				"currency", message.currency()));
	}
}
