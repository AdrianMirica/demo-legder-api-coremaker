package ro.demo.ledger_api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountApiTests {
	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void resetAccountsAndKeys() {
		jdbc.update("DELETE FROM accounts WHERE settlement_account = FALSE");
		jdbc.update("DELETE FROM idempotency_records");
	}

	@Test
	void opensAndReadsAccountWithDefaultOverdraft() throws Exception {
		String location = mockMvc.perform(post("/api/v1/accounts")
						.header("Idempotency-Key", "open-eur")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"currency\":\"EUR\"}"))
				.andExpect(status().isCreated())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.currency").value("EUR"))
				.andExpect(jsonPath("$.overdraftLimit").value(0))
				.andExpect(jsonPath("$.balance").value(0))
				.andExpect(jsonPath("$.settlementAccount").value(false))
				.andExpect(header().exists("Location"))
				.andReturn().getResponse().getHeader("Location");

		mockMvc.perform(get(location))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.currency").value("EUR"))
				.andExpect(jsonPath("$.overdraftLimit").value(0))
				.andExpect(jsonPath("$.balance").value(0))
				.andExpect(jsonPath("$.settlementAccount").value(false));
	}

	@Test
	void opensAccountWithNormalizedNonzeroOverdraftAndCurrencyScale() throws Exception {
		mockMvc.perform(post("/api/v1/accounts")
						.header("Idempotency-Key", "open-usd")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"currency\":\"USD\",\"overdraftLimit\":500.00}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.currency").value("USD"))
				.andExpect(jsonPath("$.overdraftLimit").value(500))
				.andExpect(jsonPath("$.balance").value(0));

		mockMvc.perform(post("/api/v1/accounts")
						.header("Idempotency-Key", "open-jpy")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"currency\":\"JPY\",\"overdraftLimit\":100.00}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.currency").value("JPY"))
				.andExpect(jsonPath("$.overdraftLimit").value(100));
	}

	@Test
	void idempotentRetryReplaysAccountAndLocationAndPayloadConflictReturns409() throws Exception {
		String body = "{\"currency\":\"EUR\"}";
		String location = mockMvc.perform(post("/api/v1/accounts")
						.header("Idempotency-Key", "retry-open")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getHeader("Location");

		mockMvc.perform(post("/api/v1/accounts")
						.header("Idempotency-Key", "retry-open")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", location))
				.andExpect(jsonPath("$.id").value(location.substring(location.lastIndexOf('/') + 1)));

		mockMvc.perform(post("/api/v1/accounts")
						.header("Idempotency-Key", "retry-open")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"currency\":\"USD\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REUSED"));
	}

	@Test
	void rejectsInvalidAndUnsupportedCurrencyCodes() throws Exception {
		assertCurrencyError("EURO", "INVALID_CURRENCY_CODE");
		assertCurrencyError("ABC", "INVALID_CURRENCY_CODE");
		assertCurrencyError("GBP", "UNSUPPORTED_CURRENCY");
	}

	@Test
	void rejectsNegativeAndExcessScaleOverdrafts() throws Exception {
		mockMvc.perform(post("/api/v1/accounts")
						.header("Idempotency-Key", "negative-limit")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"currency\":\"EUR\",\"overdraftLimit\":-1}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.violations[0].field").value("overdraftLimit"));

		mockMvc.perform(post("/api/v1/accounts")
						.header("Idempotency-Key", "fractional-jpy-limit")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"currency\":\"JPY\",\"overdraftLimit\":100.5}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("INVALID_AMOUNT_SCALE"));

		org.junit.jupiter.api.Assertions.assertEquals(0,
				jdbc.queryForObject("SELECT COUNT(*) FROM accounts WHERE settlement_account = FALSE",
						Integer.class));
	}

	@Test
	void idempotencyHeaderIsRequiredAndUnknownAccountReturns404() throws Exception {
		mockMvc.perform(post("/api/v1/accounts")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"currency\":\"EUR\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.violations[0].field").value("Idempotency-Key"));

		mockMvc.perform(get("/api/v1/accounts/11111111-1111-1111-1111-111111111111"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.errorCode").value("ACCOUNT_NOT_FOUND"));
	}

	@Test
	void seedsExactlyOneUnlimitedSettlementAccountPerSupportedCurrency() throws Exception {
		for (String currency : new String[] {"EUR", "USD", "JPY"}) {
			Integer count = jdbc.queryForObject(
					"SELECT COUNT(*) FROM accounts WHERE settlement_account = TRUE AND currency = ?",
					Integer.class, currency);
			org.junit.jupiter.api.Assertions.assertEquals(1, count);
		}

		mockMvc.perform(get("/api/v1/accounts/00000000-0000-0000-0000-000000000392"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.currency").value("JPY"))
				.andExpect(jsonPath("$.settlementAccount").value(true))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("\"overdraftLimit\":null")));
	}

	@ParameterizedTest
	@ValueSource(strings = {"EUR", "USD", "JPY"})
	void acceptsMaximumRepresentableOverdraftWithoutLossOfPrecision(String currency) throws Exception {
		String maximum = currency.equals("JPY") ? "99999999999999999" : "99999999999999999.99";
		String location = mockMvc.perform(post("/api/v1/accounts")
						.header("Idempotency-Key", "maximum-" + currency)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"currency\":\"" + currency + "\",\"overdraftLimit\":" + maximum + "}"))
				.andExpect(status().isCreated())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("\"overdraftLimit\":" + maximum)))
				.andReturn().getResponse().getHeader("Location");

		BigDecimal persisted = jdbc.queryForObject(
				"SELECT overdraft_limit FROM accounts WHERE id = ?", BigDecimal.class,
				location.substring(location.lastIndexOf('/') + 1));
		assertEquals(0, new BigDecimal(maximum).compareTo(persisted));
		mockMvc.perform(get(location))
				.andExpect(status().isOk())
				.andExpect(content().string(org.hamcrest.Matchers.containsString("\"overdraftLimit\":" + maximum)));
	}

	@ParameterizedTest
	@ValueSource(strings = {"EUR", "USD", "JPY"})
	void rejectsOutOfRangeOverdraftAndReplaysRejectionWithoutCreatingAccount(String currency) throws Exception {
		String payload = "{\"currency\":\"" + currency + "\",\"overdraftLimit\":100000000000000000}";
		String first = mockMvc.perform(post("/api/v1/accounts")
						.header("Idempotency-Key", "overflow")
						.contentType(MediaType.APPLICATION_JSON)
						.content(payload))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.violations[0].field").value("overdraftLimit"))
				.andExpect(jsonPath("$.violations[0].code").value("DECIMAL_MAX"))
				.andReturn().getResponse().getContentAsString();
		mockMvc.perform(post("/api/v1/accounts")
						.header("Idempotency-Key", "overflow")
						.contentType(MediaType.APPLICATION_JSON)
						.content(payload))
				.andExpect(status().isBadRequest())
				.andExpect(content().string(first));
		assertEquals(0, jdbc.queryForObject(
				"SELECT COUNT(*) FROM accounts WHERE settlement_account = FALSE", Integer.class));
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records", Integer.class));
	}

	@ParameterizedTest
	@ValueSource(strings = {"null", "\"\"", "\"12.50\"", "true", "false", "{}", "[]"})
	void rejectsExplicitNullAndNonNumericOverdraftTokens(String token) throws Exception {
		mockMvc.perform(post("/api/v1/accounts")
						.header("Idempotency-Key", "invalid-token")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"currency\":\"EUR\",\"overdraftLimit\":" + token + "}"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.violations[0].field").value("overdraftLimit"));
		assertEquals(0, jdbc.queryForObject(
				"SELECT COUNT(*) FROM accounts WHERE settlement_account = FALSE", Integer.class));
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records", Integer.class));

		mockMvc.perform(post("/api/v1/accounts")
						.header("Idempotency-Key", "invalid-token")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"currency\":\"EUR\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.overdraftLimit").value(0));
	}

	@ParameterizedTest
	@ValueSource(strings = {"\"overdraftLimt\":500", "\"settlementAccount\":true", "\"settlementAccount\":false",
			"\"balance\":100", "\"id\":\"11111111-1111-1111-1111-111111111111\""})
	void rejectsUnknownAndForbiddenPropertiesBeforeHashing(String property) throws Exception {
		String field = property.substring(1, property.indexOf('"', 1));
		mockMvc.perform(post("/api/v1/accounts")
						.header("Idempotency-Key", "unknown-field")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"currency\":\"EUR\"," + property + "}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.violations[0].field").value(field));
		assertEquals(0, jdbc.queryForObject(
				"SELECT COUNT(*) FROM accounts WHERE settlement_account = FALSE", Integer.class));
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records", Integer.class));
	}

	@Test
	void rejectsDuplicateProperties() throws Exception {
		mockMvc.perform(post("/api/v1/accounts")
						.header("Idempotency-Key", "duplicate-field")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"currency\":\"EUR\",\"overdraftLimit\":100,\"overdraftLimit\":0}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.violations[0].field").value("overdraftLimit"));
	}

	@Test
	void malformedAccountIdentifierIsAClientError() throws Exception {
		mockMvc.perform(get("/api/v1/accounts/not-a-uuid"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.violations[0].field").value("accountId"))
				.andExpect(jsonPath("$.violations[0].code").value("TYPE_MISMATCH"));
	}

	private void assertCurrencyError(String currency, String expectedCode) throws Exception {
		mockMvc.perform(post("/api/v1/accounts")
						.header("Idempotency-Key", "invalid-" + currency)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"currency\":\"" + currency + "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value(expectedCode));
	}
}
