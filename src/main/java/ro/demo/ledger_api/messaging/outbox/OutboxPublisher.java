package ro.demo.ledger_api.messaging.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class OutboxPublisher {
	private static final Logger logger = LoggerFactory.getLogger(OutboxPublisher.class);
	private static final int BATCH_SIZE = 100;
	private final OutboxEventRepository repository;
	private final RabbitTemplate rabbit;
	private final TransactionTemplate transaction;
	private final Duration confirmTimeout;

	public OutboxPublisher(OutboxEventRepository repository, RabbitTemplate rabbitTemplate,
			PlatformTransactionManager transactionManager,
			@Value("${ledger.outbox.confirm-timeout:5s}") Duration confirmTimeout) {
		this.repository = repository;
		this.rabbit = rabbitTemplate;
		this.rabbit.setMandatory(true);
		this.transaction = new TransactionTemplate(transactionManager);
		this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.confirmTimeout = confirmTimeout;
	}

	@Scheduled(fixedDelayString = "${ledger.outbox.poll-delay:250ms}",
			initialDelayString = "${ledger.outbox.initial-delay:250ms}")
	public void publishBatch() {
		transaction.executeWithoutResult(status -> {
			for (OutboxEventRepository.OutboxRecord event : repository.lockBatch(BATCH_SIZE)) {
				if (!publishLocked(event)) {
					break;
				}
			}
		});
	}

	private boolean publishLocked(OutboxEventRepository.OutboxRecord event) {
		try {
			MessageProperties properties = new MessageProperties();
			properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
			properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
			properties.setMessageId(event.id());
			properties.setCorrelationId(event.correlationId());
			properties.setHeader("eventType", event.eventType());
			CorrelationData correlation = new CorrelationData(event.id());
			rabbit.send(OutboxMessagingConfiguration.TRANSACTION_EXCHANGE, event.routingKey(),
					new Message(event.payload().getBytes(StandardCharsets.UTF_8), properties), correlation);
			CorrelationData.Confirm confirm = correlation.getFuture()
					.get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
			if (!confirm.ack()) {
				throw new IllegalStateException("Broker negatively acknowledged event: " + confirm.reason());
			}
			if (correlation.getReturned() != null) {
				throw new IllegalStateException("Broker returned event: "
						+ correlation.getReturned().getReplyText());
			}
			repository.markPublished(event.id());
			return true;
		}
		catch (Exception failure) {
			repository.markFailed(event.id(), failure);
			logger.error("Outbox publication failed: eventId={}, aggregateId={}, routingKey={}",
					event.id(), event.aggregateId(), event.routingKey(), failure);
			return false;
		}
	}
}
