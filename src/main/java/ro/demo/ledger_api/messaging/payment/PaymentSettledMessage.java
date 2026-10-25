package ro.demo.ledger_api.messaging.payment;

import java.math.BigDecimal;
import java.util.UUID;
import ro.demo.ledger_api.shared.domain.CurrencyCode;
import tools.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = PaymentSettledMessageDeserializer.class)
public record PaymentSettledMessage(
		UUID eventId,
		String paymentId,
		UUID merchantAccountId,
		BigDecimal amount,
		CurrencyCode currency) {
}
