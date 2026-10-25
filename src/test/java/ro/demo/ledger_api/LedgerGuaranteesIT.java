package ro.demo.ledger_api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import ro.demo.ledger_api.idempotency.IdempotencyService;
import ro.demo.ledger_api.idempotency.StoredHttpResponse;
import ro.demo.ledger_api.messaging.outbox.OutboxContext;
import ro.demo.ledger_api.messaging.outbox.OutboxMessagingConfiguration;
import ro.demo.ledger_api.messaging.outbox.OutboxPublisher;
import ro.demo.ledger_api.messaging.outbox.OutboxService;
import ro.demo.ledger_api.messaging.payment.PaymentProcessingService;
import ro.demo.ledger_api.messaging.payment.PaymentSettledMessage;
import ro.demo.ledger_api.shared.domain.CurrencyCode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
		"ledger.outbox.initial-delay=1h",
		"spring.rabbitmq.listener.simple.auto-startup=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LedgerGuaranteesIT {
	private static final String SETTLEMENT_EUR = "00000000-0000-0000-0000-000000000978";
	private static final String ANNOUNCEMENT_QUEUE = "ledger.guarantees.announcement.q";

	@Autowired private MockMvc mockMvc;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private ObjectMapper mapper;
	@Autowired private IdempotencyService idempotency;
	@Autowired private PaymentProcessingService payments;
	@Autowired private OutboxContext outboxContext;
	@Autowired private OutboxPublisher outboxPublisher;
	@Autowired private RabbitTemplate rabbit;
	@Autowired private AmqpAdmin admin;
	@Autowired private MySQLContainer mysql;
	@Autowired private RabbitMQContainer rabbitContainer;

	@Test
	void provesAtomicRefusalIntegrityImmutabilityAndNiceToHaveReads() throws Exception {
		String merchant = account("EUR", "0");
		String payout = account("EUR", "0");
		JsonNode funding = mapper.readTree(record("it-fund-" + UUID.randomUUID(), "IT funding",
				entry(SETTLEMENT_EUR, "DEBIT", "100.00"),
				entry(merchant, "CREDIT", "100.00")));
		long transactionsBefore = count("ledger_transactions");
		long entriesBefore = count("ledger_entries");
		long outboxBefore = count("outbox_events");
		Map<String, BigDecimal> balancesBefore = balances();
		mockMvc.perform(post("/api/v1/transactions")
						.header("Idempotency-Key", "it-refused-" + UUID.randomUUID())
						.contentType(MediaType.APPLICATION_JSON)
						.content(request("Unbalanced",
								entry(merchant, "DEBIT", "10.00"),
								entry(payout, "CREDIT", "9.00"))))
				.andExpect(status().isBadRequest());
		assertEquals(transactionsBefore, count("ledger_transactions"));
		assertEquals(entriesBefore, count("ledger_entries"));
		assertEquals(outboxBefore, count("outbox_events"));
		assertEquals(balancesBefore, balances());

		JsonNode transfer = mapper.readTree(record("it-transfer-" + UUID.randomUUID(), "IT transfer",
				entry(merchant, "DEBIT", "25.00"),
				entry(payout, "CREDIT", "25.00")));
		mockMvc.perform(post("/api/v1/transactions/{id}/reversal", transfer.get("id").asString())
						.header("Idempotency-Key", "it-reverse-" + UUID.randomUUID())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isCreated());
		UUID paymentEvent = UUID.randomUUID();
		String paymentId = "it-payment-" + UUID.randomUUID();
		outboxContext.with(paymentEvent.toString(), paymentEvent.toString(),
				() -> payments.process(new PaymentSettledMessage(paymentEvent, paymentId,
						UUID.fromString(merchant), new BigDecimal("0.30"), CurrencyCode.EUR)));
		assertCurrencyIntegrity();

		mockMvc.perform(get("/api/v1/accounts/{id}/entries", merchant)
						.queryParam("page", "0").queryParam("size", "2"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].balanceAfter").exists())
				.andExpect(jsonPath("$.totalElements").isNumber());
		Instant fundingAt = Instant.parse(funding.get("recordedAt").asString());
		mockMvc.perform(get("/api/v1/accounts/{id}/balance", merchant)
						.queryParam("at", fundingAt.toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.balance").value(100.00));

		String transactionId = funding.get("id").asString();
		String entryId = funding.at("/entries/0/id").asString();
		assertThrows(DataAccessException.class, () -> jdbc.update(
				"UPDATE ledger_transactions SET description='changed' WHERE id=?", transactionId));
		assertThrows(DataAccessException.class, () -> jdbc.update(
				"DELETE FROM ledger_entries WHERE id=?", entryId));
	}

	@Test
	void provesTwentyCompetingPostingsAndOppositeTransfers() throws Exception {
		String source = account("EUR", "0");
		record("it-concurrency-fund-" + UUID.randomUUID(), "Concurrency funding",
				entry(SETTLEMENT_EUR, "DEBIT", "100.00"),
				entry(source, "CREDIT", "100.00"));
		var executor = Executors.newFixedThreadPool(20);
		CountDownLatch ready = new CountDownLatch(20);
		CountDownLatch start = new CountDownLatch(1);
		AtomicInteger successes = new AtomicInteger();
		try {
			var futures = java.util.stream.IntStream.range(0, 20).mapToObj(index -> {
				String target = account("EUR", "0");
				return executor.submit(() -> {
					ready.countDown();
					assertTrue(start.await(10, TimeUnit.SECONDS));
					int status = postStatus("it-compete-" + index + "-" + UUID.randomUUID(),
							request("Competing " + index,
									entry(source, "DEBIT", "10.00"),
									entry(target, "CREDIT", "10.00")));
					if (status == 201) successes.incrementAndGet();
					else assertEquals(409, status);
					return null;
				});
			}).toList();
			assertTrue(ready.await(10, TimeUnit.SECONDS));
			start.countDown();
			for (var future : futures) future.get(20, TimeUnit.SECONDS);
		}
		finally {
			start.countDown();
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
		}
		assertEquals(10, successes.get());
		assertMoney(source, "0.00");

		String a = account("EUR", "0");
		String b = account("EUR", "0");
		record("it-fund-a-" + UUID.randomUUID(), "Fund A",
				entry(SETTLEMENT_EUR, "DEBIT", "50.00"), entry(a, "CREDIT", "50.00"));
		record("it-fund-b-" + UUID.randomUUID(), "Fund B",
				entry(SETTLEMENT_EUR, "DEBIT", "50.00"), entry(b, "CREDIT", "50.00"));
		for (int round = 0; round < 5; round++) {
			var pair = Executors.newFixedThreadPool(2);
			CountDownLatch go = new CountDownLatch(1);
			int iteration = round;
			try {
				var ab = pair.submit(() -> { go.await(); return postStatus("it-ab-" + iteration,
						request("A to B", entry(a, "DEBIT", "1.00"), entry(b, "CREDIT", "1.00"))); });
				var ba = pair.submit(() -> { go.await(); return postStatus("it-ba-" + iteration,
						request("B to A", entry(b, "DEBIT", "1.00"), entry(a, "CREDIT", "1.00"))); });
				go.countDown();
				assertEquals(201, ab.get(10, TimeUnit.SECONDS));
				assertEquals(201, ba.get(10, TimeUnit.SECONDS));
			}
			finally {
				go.countDown();
				pair.shutdownNow();
			}
		}
		assertMoney(a, "50.00");
		assertMoney(b, "50.00");
		assertCurrencyIntegrity();
	}

	@Test
	void provesRestIdempotencyAcrossTwoApplicationContexts() throws Exception {
		String operation = "IT_TWO_CONTEXTS_" + UUID.randomUUID();
		String key = "same-request";
		jdbc.execute("CREATE TABLE IF NOT EXISTS it_idempotency_effects (id VARCHAR(128) PRIMARY KEY)");
		try (ConfigurableApplicationContext second = new SpringApplicationBuilder(LedgerApiApplication.class)
				.web(WebApplicationType.NONE)
				.run(
						"--spring.datasource.url=" + mysql.getJdbcUrl(),
						"--spring.datasource.username=" + mysql.getUsername(),
						"--spring.datasource.password=" + mysql.getPassword(),
						"--spring.rabbitmq.addresses=" + rabbitContainer.getAmqpUrl(),
						"--spring.rabbitmq.listener.simple.auto-startup=false",
						"--ledger.outbox.initial-delay=1h",
						"--server.port=0")) {
			IdempotencyService secondService = second.getBean(IdempotencyService.class);
			JdbcTemplate secondJdbc = second.getBean(JdbcTemplate.class);
			StoredHttpResponse expected = new StoredHttpResponse(
					201, MediaType.APPLICATION_JSON_VALUE, "{\"result\":\"created\"}");
			CountDownLatch start = new CountDownLatch(1);
			var executor = Executors.newFixedThreadPool(2);
			try {
				var first = executor.submit(() -> {
					start.await();
					return idempotency.execute(operation, key, Map.of("value", 1), () -> {
						jdbc.update("INSERT INTO it_idempotency_effects VALUES (?)", operation);
						return expected;
					});
				});
				var duplicate = executor.submit(() -> {
					start.await();
					return secondService.execute(operation, key, Map.of("value", 1), () -> {
						secondJdbc.update("INSERT INTO it_idempotency_effects VALUES (?)", operation);
						return expected;
					});
				});
				start.countDown();
				assertEquals(expected, first.get(15, TimeUnit.SECONDS));
				assertEquals(expected, duplicate.get(15, TimeUnit.SECONDS));
			}
			finally {
				start.countDown();
				executor.shutdownNow();
			}
		}
		assertEquals(1, jdbc.queryForObject(
				"SELECT COUNT(*) FROM it_idempotency_effects WHERE id=?", Integer.class, operation));
	}

	@Test
	void provesPaymentRedeliveryAndOutboxRecovery() throws Exception {
		String merchant = account("EUR", "0");
		String paymentId = "it-redelivery-" + UUID.randomUUID();
		for (int index = 0; index < 5; index++) {
			UUID eventId = UUID.randomUUID();
			outboxContext.with(eventId.toString(), eventId.toString(),
					() -> payments.process(new PaymentSettledMessage(eventId, paymentId,
							UUID.fromString(merchant), new BigDecimal("5.00"), CurrencyCode.EUR)));
		}
		assertEquals(1, jdbc.queryForObject("""
				SELECT COUNT(*) FROM ledger_transactions
				WHERE source='PAYMENT' AND source_reference=?
				""", Integer.class, paymentId));

		Queue queue = new Queue(ANNOUNCEMENT_QUEUE, false, false, false);
		admin.declareQueue(queue);
		var binding = BindingBuilder.bind(queue)
				.to(new TopicExchange(OutboxMessagingConfiguration.TRANSACTION_EXCHANGE))
				.with(OutboxService.ROUTING_KEY);
		admin.declareBinding(binding);
		admin.removeBinding(binding);
		outboxPublisher.publishBatch();
		String paymentTransaction = jdbc.queryForObject(
				"SELECT transaction_id FROM processed_payments WHERE payment_id=?",
				String.class, paymentId);
		assertNull(jdbc.queryForObject(
				"SELECT published_at FROM outbox_events WHERE aggregate_id=?",
				Object.class, paymentTransaction));
		admin.declareBinding(binding);
		outboxPublisher.publishBatch();
		Message announcement = rabbit.receive(ANNOUNCEMENT_QUEUE, 5_000);
		assertNotNull(announcement);
		assertNotNull(jdbc.queryForObject(
				"SELECT published_at FROM outbox_events WHERE aggregate_id=?",
				Object.class, paymentTransaction));
	}

	private String record(String key, String description, Map<String, Object>... entries)
			throws Exception {
		return mockMvc.perform(post("/api/v1/transactions")
						.header("Idempotency-Key", key)
						.contentType(MediaType.APPLICATION_JSON)
						.content(request(description, entries)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
	}

	private int postStatus(String key, String payload) throws Exception {
		return mockMvc.perform(post("/api/v1/transactions")
						.header("Idempotency-Key", key)
						.contentType(MediaType.APPLICATION_JSON)
						.content(payload))
				.andReturn().getResponse().getStatus();
	}

	private String request(String description, Map<String, Object>... entries) {
		return mapper.writeValueAsString(Map.of("description", description, "entries", List.of(entries)));
	}

	private Map<String, Object> entry(String account, String direction, String amount) {
		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("accountId", account);
		entry.put("direction", direction);
		entry.put("amount", new BigDecimal(amount));
		return entry;
	}

	private String account(String currency, String overdraft) {
		String id = UUID.randomUUID().toString();
		jdbc.update("""
				INSERT INTO accounts
				    (id, currency, overdraft_limit, balance, settlement_account, created_at)
				VALUES (?, ?, ?, 0, FALSE, UTC_TIMESTAMP(6))
				""", id, currency, new BigDecimal(overdraft));
		return id;
	}

	private long count(String table) {
		return switch (table) {
			case "ledger_transactions" -> jdbc.queryForObject(
					"SELECT COUNT(*) FROM ledger_transactions", Long.class);
			case "ledger_entries" -> jdbc.queryForObject(
					"SELECT COUNT(*) FROM ledger_entries", Long.class);
			case "outbox_events" -> jdbc.queryForObject(
					"SELECT COUNT(*) FROM outbox_events", Long.class);
			default -> throw new IllegalArgumentException(table);
		};
	}

	private Map<String, BigDecimal> balances() {
		Map<String, BigDecimal> result = new LinkedHashMap<>();
		jdbc.query("SELECT id, balance FROM accounts ORDER BY id",
				(org.springframework.jdbc.core.RowCallbackHandler) row ->
						result.put(row.getString("id"), row.getBigDecimal("balance")));
		return result;
	}

	private void assertCurrencyIntegrity() {
		jdbc.query("SELECT currency, SUM(balance) total FROM accounts GROUP BY currency",
				(org.springframework.jdbc.core.RowCallbackHandler) row ->
						assertEquals(0, row.getBigDecimal("total").signum(),
								"Unbalanced " + row.getString("currency")));
	}

	private void assertMoney(String account, String expected) {
		assertEquals(0, jdbc.queryForObject(
				"SELECT balance FROM accounts WHERE id=?", BigDecimal.class, account)
				.compareTo(new BigDecimal(expected)));
	}
}
