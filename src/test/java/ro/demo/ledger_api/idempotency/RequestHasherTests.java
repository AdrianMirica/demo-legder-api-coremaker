package ro.demo.ledger_api.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RequestHasherTests {
	private final RequestHasher hasher = new RequestHasher(JsonMapper.builder().build());

	@Test
	void normalizesDecimalScaleAndRecursivelySortsPropertiesAndMapKeys() {
		Map<String, Object> first = new LinkedHashMap<>();
		first.put("z", List.of(Map.of("amount", new BigDecimal("125.50"))));
		first.put("a", "EUR");
		Map<String, Object> second = new LinkedHashMap<>();
		second.put("a", "EUR");
		second.put("z", List.of(Map.of("amount", new BigDecimal("125.5"))));

		assertEquals(hasher.hash("OPEN_ACCOUNT", first), hasher.hash("OPEN_ACCOUNT", second));
		assertEquals(hasher.hash("OPEN_ACCOUNT", new FirstOrder("EUR", new BigDecimal("100.00"))),
				hasher.hash("OPEN_ACCOUNT", new SecondOrder(new BigDecimal("100"), "EUR")));
	}

	@Test
	void includesOperationAndRequestDataAndPreservesArrayOrder() {
		String hash = hasher.hash("RECORD_TRANSACTION", List.of(1, 2));
		assertEquals(64, hash.length());
		assertNotEquals(hash, hasher.hash("OPEN_ACCOUNT", List.of(1, 2)));
		assertNotEquals(hash, hasher.hash("RECORD_TRANSACTION", List.of(2, 1)));
		assertNotEquals(hash, hasher.hash("RECORD_TRANSACTION", List.of(1, 3)));
		assertNotEquals(
				hasher.hash("RECORD_TRANSACTION", new BigDecimal("125.500000000000000000000000000001")),
				hasher.hash("RECORD_TRANSACTION", new BigDecimal("125.500000000000000000000000000002")));
	}

	record FirstOrder(String currency, BigDecimal amount) {
	}

	record SecondOrder(BigDecimal amount, String currency) {
	}
}
