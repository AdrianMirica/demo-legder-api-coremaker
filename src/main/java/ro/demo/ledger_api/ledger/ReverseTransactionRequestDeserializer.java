package ro.demo.ledger_api.ledger;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.exc.MismatchedInputException;

public class ReverseTransactionRequestDeserializer
		extends ValueDeserializer<ReverseTransactionRequest> {

	@Override
	public ReverseTransactionRequest getNullValue(DeserializationContext context) {
		return context.reportInputMismatch(ReverseTransactionRequest.class,
				"Reversal request must be a JSON object, not null");
	}

	@Override
	public ReverseTransactionRequest deserialize(JsonParser parser, DeserializationContext context) {
		if (parser.currentToken() != JsonToken.START_OBJECT) {
			return context.reportInputMismatch(ReverseTransactionRequest.class,
					"Expected a JSON object");
		}
		String description = null;
		boolean descriptionSeen = false;
		while (parser.nextToken() != JsonToken.END_OBJECT) {
			if (parser.currentToken() != JsonToken.PROPERTY_NAME) {
				return context.reportInputMismatch(ReverseTransactionRequest.class,
						"Expected a JSON property");
			}
			String field = parser.currentName();
			JsonToken token = parser.nextToken();
			if (!"description".equals(field)) {
				throw invalid(parser, field, "Unknown property");
			}
			if (descriptionSeen) {
				throw invalid(parser, field, "Duplicate property");
			}
			if (token != JsonToken.VALUE_STRING) {
				throw invalid(parser, field, "Description must be a non-null JSON string");
			}
			descriptionSeen = true;
			description = parser.getString();
		}
		return new ReverseTransactionRequest(description);
	}

	private MismatchedInputException invalid(JsonParser parser, String field, String message) {
		MismatchedInputException exception = MismatchedInputException.from(
				parser, ReverseTransactionRequest.class, message);
		exception.prependPath(ReverseTransactionRequest.class, field);
		return exception;
	}
}
