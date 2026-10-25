package ro.demo.ledger_api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
class AccountLedgerQueryApiTests {
	private static final String SETTLEMENT_EUR = "00000000-0000-0000-0000-000000000978";
	private static final String SETTLEMENT_JPY = "00000000-0000-0000-0000-000000000392";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ObjectMapper mapper;

	@Test
	void pagesEntriesNewestFirstWithStoredBalanceSnapshots() throws Exception {
		String merchant = account("EUR");
		String fees = account("EUR");
		JsonNode first = mapper.readTree(record("query-page-first", "First payment",
				entry(SETTLEMENT_EUR, "DEBIT", "10.00"),
				entry(merchant, "CREDIT", "10.00")));
		JsonNode second = mapper.readTree(record("query-page-second", "Second payment",
				entry(SETTLEMENT_EUR, "DEBIT", "20.00"),
				entry(merchant, "CREDIT", "20.00")));
		JsonNode third = mapper.readTree(record("query-page-third", "Split adjustment",
				entry(merchant, "DEBIT", "5.00"),
				entry(merchant, "CREDIT", "3.00"),
				entry(fees, "CREDIT", "2.00")));

		mockMvc.perform(get("/api/v1/accounts/{id}/entries", merchant)
						.queryParam("page", "0")
						.queryParam("size", "2"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(2))
				.andExpect(jsonPath("$.totalElements").value(4))
				.andExpect(jsonPath("$.totalPages").value(2))
				.andExpect(jsonPath("$.content.length()").value(2))
				.andExpect(jsonPath("$.content[0].transactionId").value(third.get("id").asString()))
				.andExpect(jsonPath("$.content[0].sequence").value(1))
				.andExpect(jsonPath("$.content[0].balanceAfter").value(28.00))
				.andExpect(jsonPath("$.content[1].transactionId").value(third.get("id").asString()))
				.andExpect(jsonPath("$.content[1].sequence").value(0))
				.andExpect(jsonPath("$.content[1].balanceAfter").value(25.00));

		mockMvc.perform(get("/api/v1/accounts/{id}/entries", merchant)
						.queryParam("page", "1")
						.queryParam("size", "2"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(2))
				.andExpect(jsonPath("$.content[0].transactionId").value(second.get("id").asString()))
				.andExpect(jsonPath("$.content[0].balanceAfter").value(30.00))
				.andExpect(jsonPath("$.content[1].transactionId").value(first.get("id").asString()))
				.andExpect(jsonPath("$.content[1].balanceAfter").value(10.00));
	}

	@Test
	void returnsHistoricalBalanceFromImmutableEntrySnapshots() throws Exception {
		String merchant = account("EUR");
		JsonNode first = mapper.readTree(record("history-first", "First",
				entry(SETTLEMENT_EUR, "DEBIT", "10.00"),
				entry(merchant, "CREDIT", "10.00")));
		Instant firstRecordedAt = Instant.parse(first.get("recordedAt").asString());
		JsonNode second = mapper.readTree(record("history-second", "Second",
				entry(SETTLEMENT_EUR, "DEBIT", "15.00"),
				entry(merchant, "CREDIT", "15.00")));
		Instant secondRecordedAt = Instant.parse(second.get("recordedAt").asString());

		mockMvc.perform(get("/api/v1/accounts/{id}/balance", merchant)
						.queryParam("at", firstRecordedAt.minusMillis(1).toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.currency").value("EUR"))
				.andExpect(jsonPath("$.balance").value(0));
		mockMvc.perform(get("/api/v1/accounts/{id}/balance", merchant)
						.queryParam("at", firstRecordedAt.toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.balance").value(10.00))
				.andExpect(jsonPath("$.asOf").value(firstRecordedAt.toString()));
		mockMvc.perform(get("/api/v1/accounts/{id}/balance", merchant)
						.queryParam("at", secondRecordedAt.toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.balance").value(25.00));
		mockMvc.perform(get("/api/v1/accounts/{id}/balance", merchant)
						.queryParam("at", Instant.now().toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.balance").value(25.00));
	}

	@Test
	void returnsJpyHistoricalBalanceWithoutFractionalPart() throws Exception {
		String merchant = account("JPY");
		JsonNode transaction = mapper.readTree(record("history-jpy", "JPY payment",
				entry(SETTLEMENT_JPY, "DEBIT", "15000"),
				entry(merchant, "CREDIT", "15000")));
		Instant at = Instant.parse(transaction.get("recordedAt").asString());

		mockMvc.perform(get("/api/v1/accounts/{id}/balance", merchant)
						.queryParam("at", at.toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.currency").value("JPY"))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("\"balance\":15000")))
				.andExpect(content().string(org.hamcrest.Matchers.not(
						org.hamcrest.Matchers.containsString("\"balance\":15000.00"))));
		mockMvc.perform(get("/api/v1/accounts/{id}/entries", merchant))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("\"amount\":15000")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString(
						"\"balanceAfter\":15000")))
				.andExpect(content().string(org.hamcrest.Matchers.not(
						org.hamcrest.Matchers.containsString("\"amount\":15000.00"))))
				.andExpect(content().string(org.hamcrest.Matchers.not(
						org.hamcrest.Matchers.containsString("\"balanceAfter\":15000.00"))));
	}

	@Test
	void rejectsUnknownAccountsInvalidPagesAndFutureHistoricalInstants() throws Exception {
		UUID unknown = UUID.randomUUID();
		mockMvc.perform(get("/api/v1/accounts/{id}/entries", unknown))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.errorCode").value("ACCOUNT_NOT_FOUND"));
		mockMvc.perform(get("/api/v1/accounts/{id}/balance", unknown)
						.queryParam("at", Instant.now().minusSeconds(1).toString()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.errorCode").value("ACCOUNT_NOT_FOUND"));

		String merchant = account("EUR");
		mockMvc.perform(get("/api/v1/accounts/{id}/entries", merchant)
						.queryParam("page", "-1"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
		mockMvc.perform(get("/api/v1/accounts/{id}/entries", merchant)
						.queryParam("size", "101"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
		mockMvc.perform(get("/api/v1/accounts/{id}/balance", merchant)
						.queryParam("at", Instant.now().plusSeconds(60).toString()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.violations[0].field").value("at"));
		mockMvc.perform(get("/api/v1/accounts/{id}/balance", merchant))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.violations[0].field").value("at"));
		mockMvc.perform(get("/api/v1/accounts/{id}/balance", merchant)
						.queryParam("at", "not-an-instant"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.violations[0].field").value("at"));
	}

	@Test
	void allJdbcQueryConnectionsUseUtcSessionTimezone() {
		assertEquals("+00:00",
				jdbc.queryForObject("SELECT @@session.time_zone", String.class));
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

	private String account(String currency) {
		String id = UUID.randomUUID().toString();
		jdbc.update("""
				INSERT INTO accounts
				    (id, currency, overdraft_limit, balance, settlement_account, created_at)
				VALUES (?, ?, 0, 0, FALSE, ?)
				""", id, currency, Instant.now());
		return id;
	}
}
