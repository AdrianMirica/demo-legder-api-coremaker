package ro.demo.ledger_api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReversalApiTests {
	private static final String SETTLEMENT_EUR = "00000000-0000-0000-0000-000000000978";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ObjectMapper mapper;

	@BeforeEach
	void clearIdempotencyKeys() {
		jdbc.update("DELETE FROM idempotency_records");
	}

	@Test
	void reversesPayoutOnceWithoutChangingOriginalAndReusesNormalReadModel() throws Exception {
		String merchant = account("0");
		String payouts = account("0");
		String fees = account("0");
		record("reversal-fund", "Fund merchant",
				entry(SETTLEMENT_EUR, "DEBIT", "125.50"),
				entry(merchant, "CREDIT", "125.50"));
		JsonNode original = mapper.readTree(record("reversal-payout", "Merchant payout",
				entry(merchant, "DEBIT", "100.00"),
				entry(payouts, "CREDIT", "98.50"),
				entry(fees, "CREDIT", "1.50")));
		String originalId = original.get("id").asString();
		String originalBefore = getTransaction(originalId);

		var firstResult = mockMvc.perform(post("/api/v1/transactions/{id}/reversal", originalId)
						.header("Idempotency-Key", "reverse-payout")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"description\":\"Reverse merchant payout\"}"))
				.andExpect(status().isCreated())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(header().exists("Location"))
				.andExpect(jsonPath("$.source").value("REVERSAL"))
				.andExpect(jsonPath("$.reversedTransactionId").value(originalId))
				.andExpect(jsonPath("$.entries[0].direction").value("CREDIT"))
				.andExpect(jsonPath("$.entries[0].amount").value(100.00))
				.andExpect(jsonPath("$.entries[1].direction").value("DEBIT"))
				.andExpect(jsonPath("$.entries[1].amount").value(98.50))
				.andExpect(jsonPath("$.entries[2].direction").value("DEBIT"))
				.andExpect(jsonPath("$.entries[2].amount").value(1.50))
				.andReturn();
		String firstBody = firstResult.getResponse().getContentAsString();
		String location = firstResult.getResponse().getHeader("Location");
		String reversalId = mapper.readTree(firstBody).get("id").asString();

		assertMoney(merchant, "125.50");
		assertMoney(payouts, "0.00");
		assertMoney(fees, "0.00");
		assertEquals(originalBefore, getTransaction(originalId));
		assertEquals(firstBody, getTransaction(reversalId));

		mockMvc.perform(post("/api/v1/transactions/{id}/reversal", originalId)
						.header("Idempotency-Key", "reverse-payout")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"description\":\"Reverse merchant payout\"}"))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", location))
				.andExpect(content().string(firstBody));

		mockMvc.perform(post("/api/v1/transactions/{id}/reversal", originalId)
						.header("Idempotency-Key", "second-reversal")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.errorCode").value("TRANSACTION_ALREADY_REVERSED"));

		mockMvc.perform(post("/api/v1/transactions/{id}/reversal", reversalId)
						.header("Idempotency-Key", "reverse-a-reversal")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("REVERSAL_NOT_ALLOWED"));

		assertEquals(1, jdbc.queryForObject("""
				SELECT COUNT(*) FROM ledger_transactions
				WHERE reversed_transaction_id = ?
				""", Integer.class, originalId));
	}

	@Test
	void absentAndEmptyBodiesShareTheDefaultDescriptionAndIdempotencyHash() throws Exception {
		String merchant = account("0");
		JsonNode original = mapper.readTree(record("default-reversal-fund", "Payment",
				entry(SETTLEMENT_EUR, "DEBIT", "10.00"),
				entry(merchant, "CREDIT", "10.00")));
		String originalId = original.get("id").asString();

		String first = mockMvc.perform(post("/api/v1/transactions/{id}/reversal", originalId)
						.header("Idempotency-Key", "default-description"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.description").value("Reversal of " + originalId))
				.andReturn().getResponse().getContentAsString();
		mockMvc.perform(post("/api/v1/transactions/{id}/reversal", originalId)
						.header("Idempotency-Key", "default-description")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isCreated())
				.andExpect(content().string(first));
	}

	@Test
	void reversalThatBreachesOverdraftLeavesNoRowsOrBalanceChanges() throws Exception {
		String debtor = account("100.00");
		String beneficiary = account("0");
		String spent = account("0");
		JsonNode original = mapper.readTree(record("overdraft-original", "Advance",
				entry(debtor, "DEBIT", "100.00"),
				entry(beneficiary, "CREDIT", "100.00")));
		record("spend-benefit", "Spend benefit",
				entry(beneficiary, "DEBIT", "100.00"),
				entry(spent, "CREDIT", "100.00"));
		int transactionsBefore = count("ledger_transactions");
		int entriesBefore = count("ledger_entries");
		Map<String, BigDecimal> balancesBefore = balances(debtor, beneficiary, spent);

		mockMvc.perform(post("/api/v1/transactions/{id}/reversal", original.get("id").asString())
						.header("Idempotency-Key", "overdraft-reversal")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.errorCode").value("OVERDRAFT_LIMIT_EXCEEDED"));

		assertEquals(transactionsBefore, count("ledger_transactions"));
		assertEquals(entriesBefore, count("ledger_entries"));
		assertEquals(balancesBefore, balances(debtor, beneficiary, spent));
		assertEquals(0, jdbc.queryForObject("""
				SELECT COUNT(*) FROM ledger_transactions
				WHERE reversed_transaction_id = ?
				""", Integer.class, original.get("id").asString()));
	}

	@Test
	void concurrentReversalsWithDifferentKeysCreateExactlyOneReversal() throws Exception {
		String merchant = account("0");
		JsonNode original = mapper.readTree(record("concurrent-reversal-fund", "Concurrent payment",
				entry(SETTLEMENT_EUR, "DEBIT", "25.00"),
				entry(merchant, "CREDIT", "25.00")));
		String originalId = original.get("id").asString();
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			var first = executor.submit(() -> reverseConcurrently(
					originalId, "concurrent-reversal-a", ready, start));
			var second = executor.submit(() -> reverseConcurrently(
					originalId, "concurrent-reversal-b", ready, start));
			assertTrue(ready.await(10, TimeUnit.SECONDS));
			start.countDown();
			List<Integer> statuses = List.of(
					first.get(15, TimeUnit.SECONDS),
					second.get(15, TimeUnit.SECONDS)).stream().sorted().toList();
			assertEquals(List.of(201, 409), statuses);
		}
		finally {
			start.countDown();
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
		}

		assertEquals(1, jdbc.queryForObject("""
				SELECT COUNT(*) FROM ledger_transactions
				WHERE reversed_transaction_id = ?
				""", Integer.class, originalId));
		assertMoney(merchant, "0.00");
	}

	@Test
	void unknownOriginalAndInvalidReversalBodyUseContractErrors() throws Exception {
		mockMvc.perform(post("/api/v1/transactions/{id}/reversal", UUID.randomUUID())
						.header("Idempotency-Key", "unknown-reversal")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.errorCode").value("TRANSACTION_NOT_FOUND"));

		String merchant = account("0");
		String originalId = mapper.readTree(record("strict-reversal-fund", "Strict payment",
				entry(SETTLEMENT_EUR, "DEBIT", "1.00"),
				entry(merchant, "CREDIT", "1.00"))).get("id").asString();
		mockMvc.perform(post("/api/v1/transactions/{id}/reversal", originalId)
						.header("Idempotency-Key", "invalid-reversal-body")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"description\":123}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.violations[0].field").value("description"));
		mockMvc.perform(post("/api/v1/transactions/{id}/reversal", originalId)
						.header("Idempotency-Key", "null-reversal-body")
						.contentType(MediaType.APPLICATION_JSON)
						.content("null"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
		mockMvc.perform(post("/api/v1/transactions/{id}/reversal", originalId)
						.header("Idempotency-Key", "blank-reversal-description")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"description\":\"   \"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.violations[0].field").value("description"));
		assertEquals(0, jdbc.queryForObject("""
				SELECT COUNT(*) FROM ledger_transactions
				WHERE reversed_transaction_id = ?
				""", Integer.class, originalId));
	}

	private int reverseConcurrently(String originalId, String key, CountDownLatch ready,
			CountDownLatch start) throws Exception {
		ready.countDown();
		assertTrue(start.await(10, TimeUnit.SECONDS));
		return mockMvc.perform(post("/api/v1/transactions/{id}/reversal", originalId)
						.header("Idempotency-Key", key)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andReturn().getResponse().getStatus();
	}

	private String getTransaction(String id) throws Exception {
		return mockMvc.perform(get("/api/v1/transactions/{id}", id))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
	}

	private String record(String key, String description, Map<String, Object>... entries)
			throws Exception {
		return mockMvc.perform(post("/api/v1/transactions")
						.header("Idempotency-Key", key)
						.contentType(MediaType.APPLICATION_JSON)
						.content(mapper.writeValueAsString(
								Map.of("description", description, "entries", List.of(entries)))))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
	}

	private Map<String, Object> entry(String accountId, String direction, String amount) {
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("accountId", accountId);
		entry.put("direction", direction);
		entry.put("amount", new BigDecimal(amount));
		return entry;
	}

	private String account(String overdraft) {
		String id = UUID.randomUUID().toString();
		jdbc.update("""
				INSERT INTO accounts
				    (id, currency, overdraft_limit, balance, settlement_account, created_at)
				VALUES (?, 'EUR', ?, 0, FALSE, ?)
				""", id, new BigDecimal(overdraft), Instant.now());
		return id;
	}

	private void assertMoney(String accountId, String expected) {
		assertEquals(0, jdbc.queryForObject(
				"SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, accountId)
				.compareTo(new BigDecimal(expected)));
	}

	private int count(String table) {
		String sql = "ledger_transactions".equals(table)
				? "SELECT COUNT(*) FROM ledger_transactions"
				: "SELECT COUNT(*) FROM ledger_entries";
		return jdbc.queryForObject(sql, Integer.class);
	}

	private Map<String, BigDecimal> balances(String... ids) {
		Map<String, BigDecimal> result = new LinkedHashMap<>();
		for (String id : ids) {
			result.put(id, jdbc.queryForObject(
					"SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, id));
		}
		return result;
	}
}
