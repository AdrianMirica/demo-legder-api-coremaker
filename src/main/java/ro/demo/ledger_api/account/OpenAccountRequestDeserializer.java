package ro.demo.ledger_api.account;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.exc.MismatchedInputException;

public class OpenAccountRequestDeserializer extends ValueDeserializer<OpenAccountRequest> {
	@Override
	public OpenAccountRequest deserialize(JsonParser parser, DeserializationContext context) {
		if (parser.currentToken() != JsonToken.START_OBJECT) {
			return context.reportInputMismatch(OpenAccountRequest.class, "Expected a JSON object");
		}
		String currency = null;
		BigDecimal overdraftLimit = null;
		Set<String> seen = new HashSet<>();
		while (parser.nextToken() != JsonToken.END_OBJECT) {
			if (parser.currentToken() != JsonToken.PROPERTY_NAME) {
				return context.reportInputMismatch(OpenAccountRequest.class, "Expected a JSON property");
			}
			String field = parser.currentName();
			JsonToken token = parser.nextToken();
			if (!seen.add(field)) {
				throw invalidField(parser, field, "Duplicate property");
			}
			switch (field) {
				case "currency" -> {
					if (token != JsonToken.VALUE_STRING) {
						throw invalidField(parser, field, "Currency must be a JSON string");
					}
					currency = parser.getString();
				}
				case "overdraftLimit" -> {
					if (token != JsonToken.VALUE_NUMBER_INT && token != JsonToken.VALUE_NUMBER_FLOAT) {
						throw invalidField(parser, field, "Overdraft limit must be a non-null JSON number");
					}
					overdraftLimit = parser.getDecimalValue();
				}
				default -> throw invalidField(parser, field, "Unknown property");
			}
		}
		return new OpenAccountRequest(currency, overdraftLimit);
	}

	private MismatchedInputException invalidField(JsonParser parser, String field, String message) {
		MismatchedInputException exception = MismatchedInputException.from(
				parser, OpenAccountRequest.class, message);
		exception.prependPath(OpenAccountRequest.class, field);
		return exception;
	}
}
