package ro.demo.ledger_api.ledger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import ro.demo.ledger_api.shared.domain.EntryDirection;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.exc.MismatchedInputException;

public class RecordTransactionRequestDeserializer
		extends ValueDeserializer<RecordTransactionRequest> {

	@Override
	public RecordTransactionRequest deserialize(JsonParser parser, DeserializationContext context) {
		requireToken(parser, context, JsonToken.START_OBJECT, "Expected a JSON object");
		String description = null;
		List<EntryRequest> entries = null;
		Set<String> seen = new HashSet<>();
		while (parser.nextToken() != JsonToken.END_OBJECT) {
			requireToken(parser, context, JsonToken.PROPERTY_NAME, "Expected a JSON property");
			String field = parser.currentName();
			JsonToken token = parser.nextToken();
			if (!seen.add(field)) {
				throw rootError(parser, field, "Duplicate property");
			}
			switch (field) {
				case "description" -> {
					if (token != JsonToken.VALUE_STRING) {
						throw rootError(parser, field, "Description must be a JSON string");
					}
					description = parser.getString();
				}
				case "entries" -> entries = readEntries(parser, context, token);
				default -> throw rootError(parser, field, "Unknown property");
			}
		}
		return new RecordTransactionRequest(description, entries);
	}

	private List<EntryRequest> readEntries(JsonParser parser, DeserializationContext context,
			JsonToken token) {
		if (token != JsonToken.START_ARRAY) {
			throw rootError(parser, "entries", "Entries must be a JSON array");
		}
		List<EntryRequest> entries = new ArrayList<>();
		while (parser.nextToken() != JsonToken.END_ARRAY) {
			int index = entries.size();
			if (parser.currentToken() != JsonToken.START_OBJECT) {
				throw entryError(parser, index, null, "Each entry must be a JSON object");
			}
			entries.add(readEntry(parser, context, index));
		}
		return entries;
	}

	private EntryRequest readEntry(JsonParser parser, DeserializationContext context, int index) {
		UUID accountId = null;
		EntryDirection direction = null;
		BigDecimal amount = null;
		Set<String> seen = new HashSet<>();
		while (parser.nextToken() != JsonToken.END_OBJECT) {
			requireToken(parser, context, JsonToken.PROPERTY_NAME, "Expected an entry property");
			String field = parser.currentName();
			JsonToken token = parser.nextToken();
			if (!seen.add(field)) {
				throw entryError(parser, index, field, "Duplicate property");
			}
			switch (field) {
				case "accountId" -> accountId = readAccountId(parser, token, index);
				case "direction" -> direction = readDirection(parser, token, index);
				case "amount" -> amount = readAmount(parser, token, index);
				default -> throw entryError(parser, index, field, "Unknown property");
			}
		}
		return new EntryRequest(accountId, direction, amount);
	}

	private UUID readAccountId(JsonParser parser, JsonToken token, int index) {
		if (token != JsonToken.VALUE_STRING) {
			throw entryError(parser, index, "accountId", "Account ID must be a UUID string");
		}
		try {
			return UUID.fromString(parser.getString());
		}
		catch (IllegalArgumentException exception) {
			throw entryError(parser, index, "accountId", "Account ID must be a valid UUID");
		}
	}

	private EntryDirection readDirection(JsonParser parser, JsonToken token, int index) {
		if (token != JsonToken.VALUE_STRING) {
			throw entryError(parser, index, "direction", "Direction must be a JSON string");
		}
		try {
			return EntryDirection.valueOf(parser.getString());
		}
		catch (IllegalArgumentException exception) {
			throw entryError(parser, index, "direction", "Direction must be DEBIT or CREDIT");
		}
	}

	private BigDecimal readAmount(JsonParser parser, JsonToken token, int index) {
		if (token != JsonToken.VALUE_NUMBER_INT && token != JsonToken.VALUE_NUMBER_FLOAT) {
			throw entryError(parser, index, "amount", "Amount must be a non-null JSON number");
		}
		return parser.getDecimalValue();
	}

	private void requireToken(JsonParser parser, DeserializationContext context,
			JsonToken expected, String message) {
		if (parser.currentToken() != expected) {
			context.reportInputMismatch(RecordTransactionRequest.class, message);
		}
	}

	private MismatchedInputException rootError(JsonParser parser, String field, String message) {
		MismatchedInputException exception = MismatchedInputException.from(
				parser, RecordTransactionRequest.class, message);
		exception.prependPath(RecordTransactionRequest.class, field);
		return exception;
	}

	private MismatchedInputException entryError(JsonParser parser, int index, String field,
			String message) {
		MismatchedInputException exception = MismatchedInputException.from(
				parser, EntryRequest.class, message);
		if (field != null) {
			exception.prependPath(EntryRequest.class, field);
		}
		exception.prependPath(List.class, index);
		exception.prependPath(RecordTransactionRequest.class, "entries");
		return exception;
	}
}
