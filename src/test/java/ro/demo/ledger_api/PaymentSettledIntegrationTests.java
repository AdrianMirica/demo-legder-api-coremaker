package ro.demo.ledger_api;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import ro.demo.ledger_api.messaging.payment.PaymentMessagingConfiguration;
import ro.demo.ledger_api.messaging.payment.PaymentProcessingService;
import ro.demo.ledger_api.messaging.payment.ProcessedPaymentRepository;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class PaymentSettledIntegrationTests {
	@Autowired
	private RabbitTemplate rabbit;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private ObjectMapper mapper;
	@Autowired
	private RabbitListenerEndpointRegistry listeners;
	@Autowired
	private RabbitMQContainer broker;
	@MockitoSpyBean
	private PaymentProcessingService processing;
	@MockitoSpyBean
	private ProcessedPaymentRepository payments;

	private AtomicInteger acknowledgments;
	private AtomicBoolean acknowledgmentBeforeCommit;
	private AtomicReference<String> expectedCommittedPaymentId;

	@BeforeEach
	void observeAcknowledgments() {
		acknowledgments = new AtomicInteger();
		acknowledgmentBeforeCommit = new AtomicBoolean();
		expectedCommittedPaymentId = new AtomicReference<>();
		var listener = (AbstractMessageListenerContainer) listeners.getListenerContainer("paymentSettledListener");
		assertNotNull(listener);
		listener.stop();
		listener.setMessageAckListener((success, tag, failure) -> {
			if (success) {
				String expectedId = expectedCommittedPaymentId.get();
				if (expectedId != null) {
					Integer committedEntries = jdbc.queryForObject("""
							SELECT COUNT(*) FROM processed_payments p
							JOIN ledger_entries e ON p.transaction_id = e.transaction_id
							WHERE p.payment_id = ?
							""", Integer.class, expectedId);
					if (committedEntries == null || committedEntries != 2) {
						acknowledgmentBeforeCommit.set(true);
					}
				}
				acknowledgments.incrementAndGet();
			}
		});
		listener.start();
	}

	@Test
	void validPaymentCommitsBeforeAckAndRedeliveriesUsePaymentIdNotEventId() {
		String merchant = account("EUR");
		String paymentId = UUID.randomUUID().toString();
		String eventId = UUID.randomUUID().toString();
		expectedCommittedPaymentId.set(paymentId);
		BigDecimal settlementBefore = balance("00000000-0000-0000-0000-000000000978");
		String payload = payment(eventId, paymentId, merchant, "125.50", "EUR");
		send(payload);
		awaitAcknowledgments(1);
		assertPayment(paymentId, merchant, "125.50");
		send(payload);
		send(payment(UUID.randomUUID().toString(), paymentId, merchant, "125.5", "EUR"));
		awaitAcknowledgments(3);
		assertPayment(paymentId, merchant, "125.50");
		assertEquals(0, balance("00000000-0000-0000-0000-000000000978")
				.compareTo(settlementBefore.subtract(new BigDecimal("125.50"))));
		assertFalse(acknowledgmentBeforeCommit.get());
		assertEquals(eventId, jdbc.queryForObject(
				"SELECT first_event_id FROM processed_payments WHERE payment_id = ?", String.class, paymentId));
	}

	@Test
	void conflictingPaymentReachesDlqWithoutChangingOriginalLedger() {
		String merchant = account("EUR");
		String paymentId = UUID.randomUUID().toString();
		send(payment(UUID.randomUUID().toString(), paymentId, merchant, "10.00", "EUR"));
		awaitAcknowledgments(1);
		String changed = payment(UUID.randomUUID().toString(), paymentId, merchant, "11.00", "EUR");
		send(changed);
		assertDeadLetter(changed);
		assertPayment(paymentId, merchant, "10.00");
		verify(processing, times(2)).process(any());
	}

	@Test
	void permanentFailuresAndMalformedMessagesAreTracedAndDoNotBlockValidMessages() {
		String merchant = account("EUR");
		String jpy = account("JPY");
		List<String> rejected = List.of(
				payment(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
						UUID.randomUUID().toString(), "10", "EUR"),
				payment(UUID.randomUUID().toString(), UUID.randomUUID().toString(), merchant, "10", "USD"),
				payment(UUID.randomUUID().toString(), UUID.randomUUID().toString(), jpy, "100.5", "JPY"),
				"{broken json",
				payment(UUID.randomUUID().toString(), UUID.randomUUID().toString(), merchant, "1", "EUR")
						.replace("\"amount\":1", "\"amount\":\"1\""),
				payment("not-a-uuid", UUID.randomUUID().toString(), merchant, "1", "EUR"),
				payment(UUID.randomUUID().toString(), UUID.randomUUID().toString(), merchant, "0", "EUR"),
				"null");
		for (String invalid : rejected) {
			send(invalid);
			assertDeadLetter(invalid);
		}
		verify(processing, times(4)).process(any());
		String validPayment = UUID.randomUUID().toString();
		send(payment(UUID.randomUUID().toString(), validPayment, merchant, "0.30", "EUR"));
		awaitAcknowledgments(1);
		assertPayment(validPayment, merchant, "0.30");
		assertEquals(0, jdbc.queryForObject(
				"SELECT COUNT(*) FROM processed_payments WHERE transaction_id IS NULL", Integer.class));
	}

	@Test
	void simultaneousDeliveriesAcrossTwoConsumersRecordOnePayment() {
		String merchant = account("JPY");
		String paymentId = UUID.randomUUID().toString();
		for (int index = 0; index < 20; index++) {
			send(payment(UUID.randomUUID().toString(), paymentId, merchant, "15000", "JPY"));
		}
		awaitAcknowledgments(20);
		assertPayment(paymentId, merchant, "15000");
	}

	@Test
	void transientFailureAfterLedgerFlushRollsBackThenRetriesAtMostThreeTimes() {
		doThrow(new TransientDataAccessResourceException("Simulated transient completion failure"))
				.doThrow(new TransientDataAccessResourceException("Simulated transient completion failure"))
				.doCallRealMethod().when(payments).complete(anyString(), anyString());
		String merchant = account("EUR");
		String paymentId = UUID.randomUUID().toString();
		send(payment(UUID.randomUUID().toString(), paymentId, merchant, "10", "EUR"));
		awaitAcknowledgments(1);
		verify(processing, times(3)).process(any());
		verify(payments, times(3)).complete(anyString(), anyString());
		assertPayment(paymentId, merchant, "10");
	}

	@Test
	void exhaustedTransientFailureIsDeadLetteredWithoutPartialWrites() {
		doThrow(new TransientDataAccessResourceException("Simulated unavailable database"))
				.when(payments).complete(anyString(), anyString());
		String merchant = account("EUR");
		String paymentId = UUID.randomUUID().toString();
		String payload = payment(UUID.randomUUID().toString(), paymentId, merchant, "10", "EUR");
		send(payload);
		assertDeadLetter(payload);
		verify(processing, times(3)).process(any());
		assertEquals(0, jdbc.queryForObject(
				"SELECT COUNT(*) FROM processed_payments WHERE payment_id = ?", Integer.class, paymentId));
		assertEquals(0, jdbc.queryForObject(
				"SELECT COUNT(*) FROM ledger_transactions WHERE source_reference = ?", Integer.class, paymentId));
		assertEquals(0, balance(merchant).signum());
	}

	@Test
	void sampleScriptPublishesPersistentJsonUsingRabbitmqadminInTheContainer() throws Exception {
		String merchant = account("EUR");
		String paymentId = "sample-\"quoted\\id-" + UUID.randomUUID();
		PublisherResult result = publishSample(paymentId, merchant);
		assertEquals(0, result.exitCode(), result.output());
		assertTrue(result.output().contains("eventId="), result.output());
		awaitAcknowledgments(1);
		assertPayment(paymentId, merchant, "12.50");
	}

	@Test
	void sampleScriptFailsWhenExchangeExistsButPaymentIsNotRouted() throws Exception {
		String merchant = account("EUR");
		String paymentId = "unrouted-" + UUID.randomUUID();
		rabbit.execute(channel -> {
			channel.queueUnbind(PaymentMessagingConfiguration.PAYMENT_QUEUE,
					PaymentMessagingConfiguration.PAYMENT_EXCHANGE,
					PaymentMessagingConfiguration.ROUTING_KEY);
			return null;
		});
		try {
			PublisherResult result = publishSample(paymentId, merchant);
			assertTrue(result.exitCode() != 0, result.output());
			assertTrue(result.output().contains("NOT routed"), result.output());
			assertTrue(result.output().contains("Check the ledger.payments binding"), result.output());
			assertEquals(0, jdbc.queryForObject(
					"SELECT COUNT(*) FROM processed_payments WHERE payment_id = ?", Integer.class, paymentId));
			assertEquals(0, balance(merchant).signum());
		}
		finally {
			rabbit.execute(channel -> {
				channel.queueBind(PaymentMessagingConfiguration.PAYMENT_QUEUE,
						PaymentMessagingConfiguration.PAYMENT_EXCHANGE,
						PaymentMessagingConfiguration.ROUTING_KEY);
				return null;
			});
		}
	}

	@ParameterizedTest
	@ValueSource(ints = {65, 128})
	void supplementaryUnicodePaymentIdsFollowCharacterLimitsAndDeduplicate(int characters) {
		String merchant = account("EUR");
		String paymentId = "\uD83D\uDCB3".repeat(characters);
		send(payment(UUID.randomUUID().toString(), paymentId, merchant, "1.00", "EUR"));
		awaitAcknowledgments(1);
		assertPayment(paymentId, merchant, "1.00");
		assertEquals("Payment settled: " + paymentId, jdbc.queryForObject("""
				SELECT description FROM ledger_transactions
				WHERE source = 'PAYMENT' AND source_reference = ?
				""", String.class, paymentId));
		send(payment(UUID.randomUUID().toString(), paymentId, merchant, "1.00", "EUR"));
		awaitAcknowledgments(2);
		assertPayment(paymentId, merchant, "1.00");
	}

	@Test
	void supplementaryUnicodePaymentIdAboveCharacterLimitIsRejectedWithoutWrites() {
		String merchant = account("EUR");
		String paymentId = "\uD83D\uDCB3".repeat(129);
		String payload = payment(UUID.randomUUID().toString(), paymentId, merchant, "1.00", "EUR");
		send(payload);
		assertDeadLetter(payload);
		verify(processing, times(1)).process(any());
		assertEquals(0, jdbc.queryForObject(
				"SELECT COUNT(*) FROM processed_payments WHERE payment_id = ?", Integer.class, paymentId));
		assertEquals(0, balance(merchant).signum());
	}

	private PublisherResult publishSample(String paymentId, String merchant) throws Exception {
		ProcessBuilder builder = new ProcessBuilder("bash", "scripts/send-payment-settled.sh",
				paymentId, merchant, "12.50", "EUR").redirectErrorStream(true);
		builder.environment().put("RABBITMQ_CONTAINER", broker.getContainerId());
		Process process = builder.start();
		try {
			assertTrue(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS), "Sample publisher timed out");
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			return new PublisherResult(process.exitValue(), output);
		}
		finally {
			if (process.isAlive()) {
				process.destroyForcibly();
			}
		}
	}

	private record PublisherResult(int exitCode, String output) {
	}

	private void send(String payload) {
		MessageProperties properties = new MessageProperties();
		properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
		properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
		properties.setMessageId(UUID.randomUUID().toString());
		rabbit.send(PaymentMessagingConfiguration.PAYMENT_EXCHANGE,
				PaymentMessagingConfiguration.ROUTING_KEY,
				new Message(payload.getBytes(StandardCharsets.UTF_8), properties));
	}

	private void assertDeadLetter(String expectedPayload) {
		Message dead = rabbit.receive(PaymentMessagingConfiguration.DEAD_LETTER_QUEUE, 10_000);
		assertNotNull(dead, "Expected invalid message in the DLQ");
		assertEquals(expectedPayload, new String(dead.getBody(), StandardCharsets.UTF_8));
		assertNotNull(dead.getMessageProperties().getHeaders().get("x-death"));
	}

	private void awaitAcknowledgments(int count) {
		await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
				assertTrue(acknowledgments.get() >= count,
						"Expected " + count + " acknowledgments, got " + acknowledgments.get()));
	}

	private String payment(String eventId, String paymentId, String accountId, String amount, String currency) {
		return mapper.writeValueAsString(Map.of(
				"eventId", eventId, "paymentId", paymentId,
				"merchantAccountId", accountId, "amount", new BigDecimal(amount), "currency", currency));
	}

	private String account(String currency) {
		String id = UUID.randomUUID().toString();
		jdbc.update("""
				INSERT INTO accounts
				    (id, currency, overdraft_limit, balance, settlement_account, created_at)
				VALUES (?, ?, 0, 0, FALSE, UTC_TIMESTAMP(6))
				""", id, currency);
		return id;
	}

	private BigDecimal balance(String accountId) {
		return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, accountId);
	}

	private void assertPayment(String paymentId, String merchant, String amount) {
		assertEquals(1, jdbc.queryForObject(
				"SELECT COUNT(*) FROM processed_payments WHERE payment_id = ?", Integer.class, paymentId));
		assertEquals(1, jdbc.queryForObject("""
				SELECT COUNT(*) FROM ledger_transactions
				WHERE source = 'PAYMENT' AND source_reference = ?
				""", Integer.class, paymentId));
		assertEquals(2, jdbc.queryForObject("""
				SELECT COUNT(*) FROM ledger_entries e
				JOIN processed_payments p ON p.transaction_id = e.transaction_id
				WHERE p.payment_id = ?
				""", Integer.class, paymentId));
		assertEquals(0, balance(merchant).compareTo(new BigDecimal(amount)));
		BigDecimal sum = jdbc.queryForObject("""
				SELECT SUM(balance) FROM accounts
				WHERE currency = (SELECT currency FROM accounts WHERE id = ?)
				""", BigDecimal.class, merchant);
		assertNotNull(sum);
		assertEquals(0, sum.signum());
	}
}
