package ro.demo.ledger_api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import ro.demo.ledger_api.messaging.outbox.OutboxContext;
import ro.demo.ledger_api.messaging.outbox.OutboxEventRepository;
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
		"ledger.outbox.poll-delay=1h"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OutboxIntegrationTests {
	private static final String CONSUMER_QUEUE = "ledger.transaction-recorded.test.q";
	private static final String SETTLEMENT_EUR = "00000000-0000-0000-0000-000000000978";

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private ObjectMapper mapper;
	@Autowired
	private RabbitTemplate rabbit;
	@Autowired
	private AmqpAdmin admin;
	@Autowired
	private OutboxPublisher publisher;
	@Autowired
	private OutboxEventRepository outboxRepository;
	@Autowired
	private PlatformTransactionManager transactionManager;
	@Autowired
	private PaymentProcessingService paymentProcessing;
	@Autowired
	private OutboxContext outboxContext;
	@Autowired
	private RabbitMQContainer broker;

	@BeforeEach
	void bindConsumerQueue() {
		jdbc.update("""
				UPDATE outbox_events
				SET published_at = COALESCE(published_at, UTC_TIMESTAMP(6))
				""");
		bindConsumer();
	}

	private void bindConsumer() {
		Queue queue = new Queue(CONSUMER_QUEUE, false, false, false);
		admin.declareQueue(queue);
		admin.declareBinding(BindingBuilder.bind(queue)
				.to(new org.springframework.amqp.core.TopicExchange(
						OutboxMessagingConfiguration.TRANSACTION_EXCHANGE))
				.with(OutboxService.ROUTING_KEY));
		admin.purgeQueue(CONSUMER_QUEUE);
	}

	@Test
	void apiTransactionCommitsExactOutboxEventAndPublishesOnlyAfterCommit() throws Exception {
		String merchant = account();
		String correlationId = "correlation-" + UUID.randomUUID();
		String idempotencyKey = "outbox-api-" + UUID.randomUUID();
		String response = record(idempotencyKey, correlationId, "Outbox API",
				entry(SETTLEMENT_EUR, "DEBIT", "12.50"),
				entry(merchant, "CREDIT", "12.50"));
		JsonNode transaction = mapper.readTree(response);
		String transactionId = transaction.get("id").asString();
		JsonNode stored = storedEvent(transactionId);

		assertEquals("transaction-recorded", stored.get("eventType").asString());
		assertEquals(1, stored.get("eventVersion").asInt());
		assertEquals(correlationId, stored.get("correlationId").asString());
		assertEquals(idempotencyKey, stored.get("causationId").asString());
		assertEquals(transactionId, stored.at("/transaction/id").asString());
		assertEquals("API", stored.at("/transaction/source").asString());
		assertEquals("EUR", stored.at("/transaction/currency").asString());
		assertTrue(stored.at("/transaction/reversedTransactionId").isNull());
		assertEquals(2, stored.at("/transaction/entries").size());
		assertEquals(transaction.at("/entries/0/balanceAfter").decimalValue(),
				stored.at("/transaction/entries/0/balanceAfter").decimalValue());
		assertNull(publishedAt(transactionId));

		publisher.publishBatch();
		Message event = rabbit.receive(CONSUMER_QUEUE, 5_000);
		assertNotNull(event);
		assertEquals(stored.get("eventId").asString(), event.getMessageProperties().getMessageId());
		assertEquals(correlationId, event.getMessageProperties().getCorrelationId());
		assertEquals(correlationId, event.getMessageProperties().getHeader("correlationId"));
		assertEquals(idempotencyKey, event.getMessageProperties().getHeader("causationId"));
		assertEquals(stored.get("eventId").asString(),
				event.getMessageProperties().getHeader("eventId"));
		assertEquals(stored, mapper.readTree(event.getBody()));
		assertNotNull(publishedAt(transactionId));
	}

	@Test
	void paymentAndReversalUseIncomingAndRestCausationIds() throws Exception {
		String merchant = account();
		String paymentId = "outbox-payment-" + UUID.randomUUID();
		UUID eventId = UUID.randomUUID();
		PaymentSettledMessage payment = new PaymentSettledMessage(
				eventId, paymentId, UUID.fromString(merchant), new BigDecimal("25.00"), CurrencyCode.EUR);
		outboxContext.with(eventId.toString(), eventId.toString(),
				() -> paymentProcessing.process(payment));
		String paymentTransaction = jdbc.queryForObject("""
				SELECT transaction_id FROM processed_payments WHERE payment_id = ?
				""", String.class, paymentId);
		JsonNode paymentEvent = storedEvent(paymentTransaction);
		assertEquals("PAYMENT", paymentEvent.at("/transaction/source").asString());
		assertEquals(eventId.toString(), paymentEvent.get("correlationId").asString());
		assertEquals(eventId.toString(), paymentEvent.get("causationId").asString());

		String reversalKey = "outbox-reversal-" + UUID.randomUUID();
		String correlation = "reversal-correlation-" + UUID.randomUUID();
		String reversal = mockMvc.perform(post("/api/v1/transactions/{id}/reversal", paymentTransaction)
						.header("Idempotency-Key", reversalKey)
						.header("X-Correlation-Id", correlation)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String reversalId = mapper.readTree(reversal).get("id").asString();
		JsonNode reversalEvent = storedEvent(reversalId);
		assertEquals("REVERSAL", reversalEvent.at("/transaction/source").asString());
		assertEquals(paymentTransaction,
				reversalEvent.at("/transaction/reversedTransactionId").asString());
		assertEquals(correlation, reversalEvent.get("correlationId").asString());
		assertEquals(reversalKey, reversalEvent.get("causationId").asString());
	}

	@Test
	void refusedTransactionRollsBackWithoutOutboxOrBrokerEvent() throws Exception {
		String merchant = account();
		long before = outboxCount();
		mockMvc.perform(post("/api/v1/transactions")
						.header("Idempotency-Key", "outbox-refused-" + UUID.randomUUID())
						.contentType(MediaType.APPLICATION_JSON)
						.content(mapper.writeValueAsString(Map.of(
								"description", "Unbalanced",
								"entries", List.of(
										entry(SETTLEMENT_EUR, "DEBIT", "10.00"),
										entry(merchant, "CREDIT", "9.00"))))))
				.andExpect(status().isBadRequest());
		assertEquals(before, outboxCount());
		publisher.publishBatch();
		assertNull(rabbit.receive(CONSUMER_QUEUE, 250));
	}

	@Test
	void returnedPublicationRetainsRowThenBindingRecoveryPublishesIt() throws Exception {
		String merchant = account();
		String response = record("outbox-recovery-" + UUID.randomUUID(), null, "Recovery",
				entry(SETTLEMENT_EUR, "DEBIT", "3.00"),
				entry(merchant, "CREDIT", "3.00"));
		String transactionId = mapper.readTree(response).get("id").asString();
		admin.removeBinding(BindingBuilder.bind(new Queue(CONSUMER_QUEUE))
				.to(new org.springframework.amqp.core.TopicExchange(
						OutboxMessagingConfiguration.TRANSACTION_EXCHANGE))
				.with(OutboxService.ROUTING_KEY));
		publisher.publishBatch();
		assertNull(publishedAt(transactionId));
		assertEquals(1, attempts(transactionId));
		assertNotNull(lastError(transactionId));

		bindConsumer();
		publisher.publishBatch();
		assertNotNull(rabbit.receive(CONSUMER_QUEUE, 5_000));
		assertNotNull(publishedAt(transactionId));
	}

	@Test
	void brokerOutageRetainsCommittedEventAndPublishesAfterRecovery() throws Exception {
		String merchant = account();
		String transactionId = mapper.readTree(record(
				"outbox-broker-outage-" + UUID.randomUUID(), null, "Broker outage",
				entry(SETTLEMENT_EUR, "DEBIT", "3.50"),
				entry(merchant, "CREDIT", "3.50"))).get("id").asString();
		String containerId = broker.getContainerId();
		broker.getDockerClient().pauseContainerCmd(containerId).exec();
		try {
			publisher.publishBatch();
			assertNull(publishedAt(transactionId));
			assertEquals(1, attempts(transactionId));
			assertNotNull(lastError(transactionId));
		}
		finally {
			broker.getDockerClient().unpauseContainerCmd(containerId).exec();
		}

		publisher.publishBatch();
		Message event = rabbit.receive(CONSUMER_QUEUE, 5_000);
		assertNotNull(event);
		assertNotNull(publishedAt(transactionId));
	}

	@Test
	void crashAfterPublishBeforeMarkCanDuplicateWithStableEventId() throws Exception {
		String merchant = account();
		String transactionId = mapper.readTree(record(
				"outbox-duplicate-" + UUID.randomUUID(), null, "Duplicate",
				entry(SETTLEMENT_EUR, "DEBIT", "4.00"),
				entry(merchant, "CREDIT", "4.00"))).get("id").asString();
		String eventId = storedEvent(transactionId).get("eventId").asString();
		publisher.publishBatch();
		Message first = rabbit.receive(CONSUMER_QUEUE, 5_000);
		assertNotNull(first);
		jdbc.update("""
				UPDATE outbox_events SET published_at = NULL
				WHERE aggregate_id = ?
				""", transactionId);
		publisher.publishBatch();
		Message duplicate = rabbit.receive(CONSUMER_QUEUE, 5_000);
		assertNotNull(duplicate);
		assertEquals(eventId, first.getMessageProperties().getMessageId());
		assertEquals(eventId, duplicate.getMessageProperties().getMessageId());
		assertEquals(new String(first.getBody(), StandardCharsets.UTF_8),
				new String(duplicate.getBody(), StandardCharsets.UTF_8));
	}

	@Test
	void twoPublisherInstancesUseSkipLockedWithoutPublishingOneRowTwice() throws Exception {
		String merchant = account();
		String transactionId = mapper.readTree(record(
				"outbox-concurrent-" + UUID.randomUUID(), null, "Concurrent publishers",
				entry(SETTLEMENT_EUR, "DEBIT", "5.00"),
				entry(merchant, "CREDIT", "5.00"))).get("id").asString();
		String eventId = storedEvent(transactionId).get("eventId").asString();
		OutboxPublisher second = new OutboxPublisher(outboxRepository, rabbit, transactionManager,
				Duration.ofSeconds(5), mapper);
		var executor = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			var firstRun = executor.submit(() -> runPublisher(publisher, ready, start));
			var secondRun = executor.submit(() -> runPublisher(second, ready, start));
			assertTrue(ready.await(10, TimeUnit.SECONDS));
			start.countDown();
			firstRun.get(10, TimeUnit.SECONDS);
			secondRun.get(10, TimeUnit.SECONDS);
		}
		finally {
			start.countDown();
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
		}
		Message first = rabbit.receive(CONSUMER_QUEUE, 5_000);
		assertNotNull(first);
		assertEquals(eventId, first.getMessageProperties().getMessageId());
		assertNull(rabbit.receive(CONSUMER_QUEUE, 500));
		assertNotNull(publishedAt(transactionId));
	}

	private void runPublisher(OutboxPublisher publisher, CountDownLatch ready, CountDownLatch start) {
		ready.countDown();
		try {
			assertTrue(start.await(10, TimeUnit.SECONDS));
			publisher.publishBatch();
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(exception);
		}
	}

	private String record(String key, String correlationId, String description,
			Map<String, Object>... entries) throws Exception {
		var request = post("/api/v1/transactions")
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(mapper.writeValueAsString(
						Map.of("description", description, "entries", List.of(entries))));
		if (correlationId != null) {
			request.header("X-Correlation-Id", correlationId);
		}
		return mockMvc.perform(request)
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

	private String account() {
		String id = UUID.randomUUID().toString();
		jdbc.update("""
				INSERT INTO accounts
				    (id, currency, overdraft_limit, balance, settlement_account, created_at)
				VALUES (?, 'EUR', 0, 0, FALSE, UTC_TIMESTAMP(6))
				""", id);
		return id;
	}

	private JsonNode storedEvent(String transactionId) {
		return mapper.readTree(jdbc.queryForObject("""
				SELECT payload FROM outbox_events WHERE aggregate_id = ?
				""", String.class, transactionId));
	}

	private Object publishedAt(String transactionId) {
		return jdbc.queryForObject("""
				SELECT published_at FROM outbox_events WHERE aggregate_id = ?
				""", Object.class, transactionId);
	}

	private int attempts(String transactionId) {
		return jdbc.queryForObject("""
				SELECT attempts FROM outbox_events WHERE aggregate_id = ?
				""", Integer.class, transactionId);
	}

	private String lastError(String transactionId) {
		return jdbc.queryForObject("""
				SELECT last_error FROM outbox_events WHERE aggregate_id = ?
				""", String.class, transactionId);
	}

	private long outboxCount() {
		return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Long.class);
	}
}
