package ro.demo.ledger_api.messaging.payment;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import ro.demo.ledger_api.shared.domain.CurrencyCode;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.exc.MismatchedInputException;

public class PaymentSettledMessageDeserializer extends ValueDeserializer<PaymentSettledMessage> {
	@Override
	public PaymentSettledMessage getNullValue(DeserializationContext context) {
		return context.reportInputMismatch(PaymentSettledMessage.class, "Payment must be a JSON object");
	}

	@Override
	public PaymentSettledMessage deserialize(JsonParser parser, DeserializationContext context) {
		if (parser.currentToken() != JsonToken.START_OBJECT) {
			return context.reportInputMismatch(PaymentSettledMessage.class, "Expected a payment object");
		}
		UUID eventId = null;
		String paymentId = null;
		UUID merchantAccountId = null;
		BigDecimal amount = null;
		CurrencyCode currency = null;
		Set<String> seen = new HashSet<>();
		while (parser.nextToken() != JsonToken.END_OBJECT) {
			if (parser.currentToken() != JsonToken.PROPERTY_NAME) {
				return context.reportInputMismatch(PaymentSettledMessage.class, "Expected a property");
			}
			String field = parser.currentName();
			JsonToken token = parser.nextToken();
			if (!seen.add(field)) {
				throw invalid(parser, field, "Duplicate property");
			}
			switch (field) {
				case "eventId" -> eventId = readUuid(parser, field, token);
				case "paymentId" -> paymentId = readString(parser, field, token);
				case "merchantAccountId" -> merchantAccountId = readUuid(parser, field, token);
				case "amount" -> {
					if (token != JsonToken.VALUE_NUMBER_INT && token != JsonToken.VALUE_NUMBER_FLOAT) {
						throw invalid(parser, field, "Amount must be a non-null JSON number");
					}
					amount = parser.getDecimalValue();
				}
				case "currency" -> {
					String code = readString(parser, field, token);
					try {
						currency = CurrencyCode.valueOf(code);
					}
					catch (IllegalArgumentException exception) {
						throw invalid(parser, field, "Supported currencies are EUR, USD, JPY");
					}
				}
				default -> throw invalid(parser, field, "Unknown property");
			}
		}
		return new PaymentSettledMessage(eventId, paymentId, merchantAccountId, amount, currency);
	}

	private String readString(JsonParser parser, String field, JsonToken token) {
		if (token != JsonToken.VALUE_STRING) {
			throw invalid(parser, field, "Expected a JSON string");
		}
		return parser.getString();
	}

	private UUID readUuid(JsonParser parser, String field, JsonToken token) {
		String value = readString(parser, field, token);
		try {
			UUID uuid = UUID.fromString(value);
			if (!uuid.toString().equalsIgnoreCase(value)) {
				throw invalid(parser, field, "Expected a canonical UUID");
			}
			return uuid;
		}
		catch (IllegalArgumentException exception) {
			throw invalid(parser, field, "Expected a valid UUID");
		}
	}

	private MismatchedInputException invalid(JsonParser parser, String field, String message) {
		MismatchedInputException exception = MismatchedInputException.from(
				parser, PaymentSettledMessage.class, message);
		exception.prependPath(PaymentSettledMessage.class, field);
		return exception;
	}
}
