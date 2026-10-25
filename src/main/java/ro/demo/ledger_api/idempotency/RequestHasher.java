package ro.demo.ledger_api.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public class RequestHasher {
	private final ObjectMapper canonicalMapper;

	public RequestHasher(ObjectMapper mapper) {
		this.canonicalMapper = mapper.rebuild()
				.enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
				.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
				.build();
	}

	public String hash(String operation, Object request) {
		JsonNode tree = canonicalize(canonicalMapper.valueToTree(request));
		String canonicalRequest = operation + "\n" + canonicalMapper.writeValueAsString(tree);
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(canonicalRequest.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available", exception);
		}
	}

	private JsonNode canonicalize(JsonNode node) {
		if (node.isObject()) {
			ObjectNode sorted = canonicalMapper.createObjectNode();
			node.properties().stream().sorted(java.util.Map.Entry.comparingByKey())
					.forEach(entry -> sorted.set(entry.getKey(), canonicalize(entry.getValue())));
			return sorted;
		}
		if (node.isArray()) {
			ArrayNode array = canonicalMapper.createArrayNode();
			node.forEach(element -> array.add(canonicalize(element)));
			return array;
		}
		if (node.isNumber()) {
			return canonicalMapper.getNodeFactory().numberNode(node.decimalValue().stripTrailingZeros());
		}
		return node;
	}
}
