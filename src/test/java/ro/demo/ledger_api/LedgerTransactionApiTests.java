package ro.demo.ledger_api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.stream.Stream;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LedgerTransactionApiTests {
	private static final String SETTLEMENT_EUR = "00000000-0000-0000-0000-000000000978";
	private static final String SETTLEMENT_JPY = "00000000-0000-0000-0000-000000000392";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ObjectMapper mapper;

	@BeforeEach
	void resetMutableTestData() {
		jdbc.update("DELETE FROM idempotency_records");
	}

	@Test
	void recordsAndReadsTheSettlementExampleAtomically() throws Exception {
		String merchant = account("EUR", "0");
		BigDecimal settlementBefore = money(SETTLEMENT_EUR);
		String response = record("settlement-example", "Payment settled",
				entry(SETTLEMENT_EUR, "DEBIT", "125.50"),
				entry(merchant, "CREDIT", "125.50"));
		JsonNode transaction = mapper.readTree(response);

		BigDecimal expectedSettlement = settlementBefore.subtract(new BigDecimal("125.50"));
		assertEquals(0, money(SETTLEMENT_EUR).compareTo(expectedSettlement));
		assertEquals(0, money(merchant).compareTo(new BigDecimal("125.50")));
		assertEquals("API", transaction.get("source").asString());
		assertEquals(2, transaction.get("entries").size());
		assertEquals(0, transaction.get("entries").get(0).get("sequence").asInt());
		assertEquals(0, transaction.get("entries").get(0).get("balanceAfter")
				.decimalValue().compareTo(expectedSettlement));
		assertEquals(0, transaction.get("entries").get(1).get("balanceAfter")
				.decimalValue().compareTo(new BigDecimal("125.50")));

		mockMvc.perform(get("/api/v1/transactions/" + transaction.get("id").asString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.description").value("Payment settled"))
				.andExpect(jsonPath("$.source").value("API"))
				.andExpect(jsonPath("$.reversedTransactionId").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.entries[0].sequence").value(0))
				.andExpect(jsonPath("$.entries[1].sequence").value(1));
	}

	@Test
	void recordsTheThreeEntryPayoutAndPreservesIntegrity() throws Exception {
		String merchant = account("EUR", "0");
		String payouts = account("EUR", "0");
		String fees = account("EUR", "0");
		record("fund-merchant", "Fund merchant",
				entry(SETTLEMENT_EUR, "DEBIT", "125.50"),
				entry(merchant, "CREDIT", "125.50"));

		record("payout", "Merchant payout",
				entry(merchant, "DEBIT", "100.00"),
				entry(payouts, "CREDIT", "98.50"),
				entry(fees, "CREDIT", "1.50"));

		assertMoney(merchant, "25.50");
		assertMoney(payouts, "98.50");
		assertMoney(fees, "1.50");
		assertCurrencyIntegrity();
	}

	@Test
	void refusesEveryInvalidTransactionWithoutLedgerOrBalanceChanges() throws Exception {
		String merchantEur = account("EUR", "0");
		String payoutsEur = account("EUR", "0");
		String merchantJpy = account("JPY", "0");
		String payoutsJpy = account("JPY", "0");
		record("initial-funds", "Initial funds",
				entry(SETTLEMENT_EUR, "DEBIT", "25.50"),
				entry(merchantEur, "CREDIT", "25.50"));

		assertRefusedUnchanged("one-entry", 400, "VALIDATION_FAILED",
				request("One entry", entry(merchantEur, "CREDIT", "10.00")));
		assertRefusedUnchanged("zero", 400, "VALIDATION_FAILED",
				request("Zero", entry(merchantEur, "DEBIT", "0"),
						entry(payoutsEur, "CREDIT", "0")));
		assertRefusedUnchanged("negative", 400, "VALIDATION_FAILED",
				request("Negative", entry(merchantEur, "DEBIT", "-1"),
						entry(payoutsEur, "CREDIT", "-1")));
		assertRefusedUnchanged("jpy-scale", 400, "INVALID_AMOUNT_SCALE",
				request("JPY decimals", entry(merchantJpy, "DEBIT", "100.5"),
						entry(payoutsJpy, "CREDIT", "100.5")));
		assertRefusedUnchanged("unknown", 404, "ACCOUNT_NOT_FOUND",
				request("Unknown account", entry(UUID.randomUUID().toString(), "DEBIT", "1.00"),
						entry(payoutsEur, "CREDIT", "1.00")));
		assertRefusedUnchanged("mixed", 400, "CURRENCY_MISMATCH",
				request("Mixed currency", entry(merchantEur, "DEBIT", "10.00"),
						entry(merchantJpy, "CREDIT", "10")));
		assertRefusedUnchanged("unbalanced", 400, "UNBALANCED_TRANSACTION",
				request("Unbalanced", entry(merchantEur, "DEBIT", "10.00"),
						entry(payoutsEur, "CREDIT", "9.00")));
		assertRefusedUnchanged("overdraft", 409, "OVERDRAFT_LIMIT_EXCEEDED",
				request("Overdraft", entry(merchantEur, "DEBIT", "50.00"),
						entry(payoutsEur, "CREDIT", "50.00")));
	}

	@Test
	void retryReturnsTheOriginalTransactionWithoutDuplicateRows() throws Exception {
		String merchant = account("EUR", "0");
		String payload = request("Idempotent settlement",
				entry(SETTLEMENT_EUR, "DEBIT", "10.00"),
				entry(merchant, "CREDIT", "10.00"));
		String first = postTransaction("same-transaction", payload, 201);
		String second = postTransaction("same-transaction", payload, 201);

		assertEquals(first, second);
		assertEquals(1, jdbc.queryForObject(
				"SELECT COUNT(*) FROM ledger_transactions WHERE description = 'Idempotent settlement'",
				Integer.class));
		assertEquals(2, jdbc.queryForObject("""
				SELECT COUNT(*) FROM ledger_entries e
				JOIN ledger_transactions t ON t.id = e.transaction_id
				WHERE t.description = 'Idempotent settlement'
				""", Integer.class));
		assertMoney(merchant, "10.00");
	}

	@Test
	void competingDebitsCannotLoseUpdatesOrBreakTheOverdraftLimit() throws Exception {
		String source = account("EUR", "0");
		String targetA = account("EUR", "0");
		String targetB = account("EUR", "0");
		record("fund-competing-source", "Fund competing source",
				entry(SETTLEMENT_EUR, "DEBIT", "100.00"),
				entry(source, "CREDIT", "100.00"));

		String firstPayload = request("Competing A",
				entry(source, "DEBIT", "80.00"), entry(targetA, "CREDIT", "80.00"));
		String secondPayload = request("Competing B",
				entry(source, "DEBIT", "80.00"), entry(targetB, "CREDIT", "80.00"));
		List<Integer> statuses = concurrentPosts(
				new Request("competing-a", firstPayload),
				new Request("competing-b", secondPayload));

		assertEquals(List.of(201, 409), statuses.stream().sorted().toList());
		assertMoney(source, "20.00");
		assertEquals(0, money(targetA).add(money(targetB)).compareTo(new BigDecimal("80.00")));
		assertCurrencyIntegrity();
	}

	@Test
	void oppositeTransfersAcquireLocksInOneOrderAndDoNotDeadlock() throws Exception {
		String accountA = account("EUR", "0");
		String accountB = account("EUR", "0");
		record("fund-a", "Fund A",
				entry(SETTLEMENT_EUR, "DEBIT", "100.00"),
				entry(accountA, "CREDIT", "100.00"));
		record("fund-b", "Fund B",
				entry(SETTLEMENT_EUR, "DEBIT", "100.00"),
				entry(accountB, "CREDIT", "100.00"));

		for (int iteration = 0; iteration < 5; iteration++) {
			List<Integer> statuses = concurrentPosts(
					new Request("a-to-b-" + iteration, request("A to B " + iteration,
							entry(accountA, "DEBIT", "10.00"),
							entry(accountB, "CREDIT", "10.00"))),
					new Request("b-to-a-" + iteration, request("B to A " + iteration,
							entry(accountB, "DEBIT", "10.00"),
							entry(accountA, "CREDIT", "10.00"))));
			assertEquals(List.of(201, 201), statuses);
		}
		assertMoney(accountA, "100.00");
		assertMoney(accountB, "100.00");
		assertCurrencyIntegrity();
	}

	@Test
	void databaseTriggersRejectUpdatesAndDeletesOfTransactionsAndEntries() throws Exception {
		String merchant = account("EUR", "0");
		JsonNode transaction = mapper.readTree(record("immutable", "Immutable transaction",
				entry(SETTLEMENT_EUR, "DEBIT", "1.00"),
				entry(merchant, "CREDIT", "1.00")));
		String transactionId = transaction.get("id").asString();
		String entryId = transaction.get("entries").get(0).get("id").asString();

		assertThrows(DataAccessException.class, () -> jdbc.update(
				"UPDATE ledger_transactions SET description = 'changed' WHERE id = ?", transactionId));
		assertThrows(DataAccessException.class, () -> jdbc.update(
				"DELETE FROM ledger_transactions WHERE id = ?", transactionId));
		assertThrows(DataAccessException.class, () -> jdbc.update(
				"UPDATE ledger_entries SET amount = 2 WHERE id = ?", entryId));
		assertThrows(DataAccessException.class, () -> jdbc.update(
				"DELETE FROM ledger_entries WHERE id = ?", entryId));
	}

	@Test
	void unknownTransactionReturns404AndMalformedIdReturns400() throws Exception {
		mockMvc.perform(get("/api/v1/transactions/" + UUID.randomUUID()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.errorCode").value("TRANSACTION_NOT_FOUND"));
		mockMvc.perform(get("/api/v1/transactions/not-a-uuid"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.violations[0].field").value("transactionId"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidJsonContracts")
	void rejectsScalarCoercionAndReportsTheExactNestedField(String name, String payload,
			String expectedField) throws Exception {
		int transactionsBefore = count("ledger_transactions");
		int entriesBefore = count("ledger_entries");
		Map<String, BigDecimal> balancesBefore = balances();

		mockMvc.perform(post("/api/v1/transactions")
						.header("Idempotency-Key", "strict-" + name)
						.contentType(MediaType.APPLICATION_JSON)
						.content(payload))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.violations[0].field").value(expectedField));

		assertEquals(transactionsBefore, count("ledger_transactions"));
		assertEquals(entriesBefore, count("ledger_entries"));
		assertEquals(balancesBefore, balances());
		assertEquals(0, jdbc.queryForObject(
				"SELECT COUNT(*) FROM idempotency_records WHERE idempotency_key = ?",
				Integer.class, "strict-" + name));
	}

	private static Stream<Arguments> invalidJsonContracts() {
		String validAccount = "11111111-1111-1111-1111-111111111111";
		String secondAccount = "22222222-2222-2222-2222-222222222222";
		return Stream.of(
				Arguments.of("numeric-description", """
						{"description":123,"entries":[
						  {"accountId":"%s","direction":"DEBIT","amount":1},
						  {"accountId":"%s","direction":"CREDIT","amount":1}
						]}
						""".formatted(validAccount, secondAccount), "description"),
				Arguments.of("string-amount", """
						{"description":"bad amount","entries":[
						  {"accountId":"%s","direction":"DEBIT","amount":"1.00"},
						  {"accountId":"%s","direction":"CREDIT","amount":1.00}
						]}
						""".formatted(validAccount, secondAccount), "entries[0].amount"),
				Arguments.of("null-amount", """
						{"description":"bad amount","entries":[
						  {"accountId":"%s","direction":"DEBIT","amount":null},
						  {"accountId":"%s","direction":"CREDIT","amount":1.00}
						]}
						""".formatted(validAccount, secondAccount), "entries[0].amount"),
				Arguments.of("unknown-entry-field", """
						{"description":"unknown field","entries":[
						  {"accountId":"%s","direction":"DEBIT","amount":1.00,"bogus":true},
						  {"accountId":"%s","direction":"CREDIT","amount":1.00}
						]}
						""".formatted(validAccount, secondAccount), "entries[0].bogus"),
				Arguments.of("invalid-account-id", """
						{"description":"bad id","entries":[
						  {"accountId":"not-a-uuid","direction":"DEBIT","amount":1.00},
						  {"accountId":"%s","direction":"CREDIT","amount":1.00}
						]}
						""".formatted(secondAccount), "entries[0].accountId"),
				Arguments.of("invalid-direction-type", """
						{"description":"bad direction","entries":[
						  {"accountId":"%s","direction":1,"amount":1.00},
						  {"accountId":"%s","direction":"CREDIT","amount":1.00}
						]}
						""".formatted(validAccount, secondAccount), "entries[0].direction"),
				Arguments.of("duplicate-entry-field", """
						{"description":"duplicate","entries":[
						  {"accountId":"%s","direction":"DEBIT","amount":1.00,"amount":2.00},
						  {"accountId":"%s","direction":"CREDIT","amount":1.00}
						]}
						""".formatted(validAccount, secondAccount), "entries[0].amount"));
	}

	private void assertRefusedUnchanged(String key, int expectedStatus, String errorCode,
			String payload) throws Exception {
		int transactionsBefore = count("ledger_transactions");
		int entriesBefore = count("ledger_entries");
		Map<String, BigDecimal> balancesBefore = balances();

		mockMvc.perform(post("/api/v1/transactions")
						.header("Idempotency-Key", key)
						.contentType(MediaType.APPLICATION_JSON)
						.content(payload))
				.andExpect(status().is(expectedStatus))
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.errorCode").value(errorCode));

		assertEquals(transactionsBefore, count("ledger_transactions"));
		assertEquals(entriesBefore, count("ledger_entries"));
		assertEquals(balancesBefore, balances());
	}

	private String record(String key, String description, Map<String, Object>... entries) throws Exception {
		return postTransaction(key, request(description, entries), 201);
	}

	private String postTransaction(String key, String payload, int expectedStatus) throws Exception {
		return mockMvc.perform(post("/api/v1/transactions")
						.header("Idempotency-Key", key)
						.contentType(MediaType.APPLICATION_JSON)
						.content(payload))
				.andExpect(status().is(expectedStatus))
				.andExpect(content().contentTypeCompatibleWith(
						expectedStatus == 201 ? MediaType.APPLICATION_JSON : MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(expectedStatus == 201 ? header().exists("Location") : header().doesNotExist("Location"))
				.andReturn().getResponse().getContentAsString();
	}

	private String request(String description, Map<String, Object>... entries) {
		return mapper.writeValueAsString(Map.of("description", description, "entries", List.of(entries)));
	}

	private Map<String, Object> entry(String accountId, String direction, String amount) {
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("accountId", accountId);
		entry.put("direction", direction);
		entry.put("amount", new BigDecimal(amount));
		return entry;
	}

	private String account(String currency, String overdraftLimit) {
		String id = UUID.randomUUID().toString();
		jdbc.update("""
				INSERT INTO accounts
				    (id, currency, overdraft_limit, balance, settlement_account, created_at)
				VALUES (?, ?, ?, 0, FALSE, ?)
				""", id, currency, new BigDecimal(overdraftLimit), Instant.now());
		return id;
	}

	private BigDecimal money(String accountId) {
		return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, accountId);
	}

	private void assertMoney(String accountId, String expected) {
		assertEquals(0, money(accountId).compareTo(new BigDecimal(expected)));
	}

	private int count(String table) {
		if ("ledger_transactions".equals(table)) {
			return jdbc.queryForObject("SELECT COUNT(*) FROM ledger_transactions", Integer.class);
		}
		return jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entries", Integer.class);
	}

	private Map<String, BigDecimal> balances() {
		Map<String, BigDecimal> result = new LinkedHashMap<>();
		jdbc.query("SELECT id, balance FROM accounts ORDER BY id",
				(RowCallbackHandler) row ->
						result.put(row.getString("id"), row.getBigDecimal("balance")));
		return result;
	}

	private void assertCurrencyIntegrity() {
		jdbc.query("""
				SELECT currency, SUM(balance) AS total
				FROM accounts
				GROUP BY currency
				""", (RowCallbackHandler) row ->
						assertEquals(0, BigDecimal.ZERO.compareTo(row.getBigDecimal("total")),
								"Unbalanced currency " + row.getString("currency")));
	}

	private List<Integer> concurrentPosts(Request first, Request second) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			var firstResult = executor.submit(() -> concurrentPost(first, ready, start));
			var secondResult = executor.submit(() -> concurrentPost(second, ready, start));
			assertTrue(ready.await(10, TimeUnit.SECONDS));
			start.countDown();
			return List.of(firstResult.get(15, TimeUnit.SECONDS),
					secondResult.get(15, TimeUnit.SECONDS));
		}
		finally {
			start.countDown();
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
		}
	}

	private int concurrentPost(Request request, CountDownLatch ready, CountDownLatch start)
			throws Exception {
		ready.countDown();
		assertTrue(start.await(10, TimeUnit.SECONDS));
		return mockMvc.perform(post("/api/v1/transactions")
						.header("Idempotency-Key", request.key())
						.contentType(MediaType.APPLICATION_JSON)
						.content(request.payload()))
				.andReturn().getResponse().getStatus();
	}

	private record Request(String key, String payload) {
	}
}
