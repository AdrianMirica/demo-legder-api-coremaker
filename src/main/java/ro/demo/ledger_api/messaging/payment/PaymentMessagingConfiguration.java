package ro.demo.ledger_api.messaging.payment;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;
import ro.demo.ledger_api.shared.api.ApiException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class PaymentMessagingConfiguration {
	public static final String PAYMENT_EXCHANGE = "ledger.payments";
	public static final String PAYMENT_QUEUE = "ledger.payment-settled.q";
	public static final String DEAD_LETTER_EXCHANGE = "ledger.payments.dlx";
	public static final String DEAD_LETTER_QUEUE = "ledger.payment-settled.dlq";
	public static final String ROUTING_KEY = "payment.settled.v1";
	private static final Logger logger = LoggerFactory.getLogger(PaymentMessagingConfiguration.class);

	@Bean
	Declarables paymentTopology() {
		var exchange = new TopicExchange(PAYMENT_EXCHANGE, true, false);
		var deadExchange = new TopicExchange(DEAD_LETTER_EXCHANGE, true, false);
		var queue = QueueBuilder.durable(PAYMENT_QUEUE)
				.deadLetterExchange(DEAD_LETTER_EXCHANGE)
				.deadLetterRoutingKey(ROUTING_KEY)
				.build();
		var deadQueue = QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
		return new Declarables(exchange, deadExchange, queue, deadQueue,
				BindingBuilder.bind(queue).to(exchange).with(ROUTING_KEY),
				BindingBuilder.bind(deadQueue).to(deadExchange).with(ROUTING_KEY));
	}

	@Bean
	SimpleRabbitListenerContainerFactory paymentListenerContainerFactory(
			SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory,
			JsonMapper mapper) {
		var factory = new SimpleRabbitListenerContainerFactory();
		configurer.configure(factory, connectionFactory);
		var converter = new JacksonJsonMessageConverter(mapper.rebuild()
				.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build());
		converter.setAlwaysConvertToInferredType(true);
		converter.setAssumeSupportedContentType(false);
		factory.setMessageConverter(converter);
		factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
		factory.setDefaultRequeueRejected(false);
		factory.setPrefetchCount(1);
		factory.setConcurrentConsumers(2);
		factory.setAdviceChain(RetryInterceptorBuilder.stateless()
				.retryPolicy(RetryPolicy.builder().maxRetries(2)
						.delay(Duration.ofMillis(100)).multiplier(2).maxDelay(Duration.ofMillis(400))
						.predicate(PaymentMessagingConfiguration::retryable)
						.build())
				.recoverer((message, failure) -> {
					logDeadLetter(mapper, message, failure);
					throw new AmqpRejectAndDontRequeueException("Payment rejected to DLQ", failure);
				})
				.build());
		return factory;
	}

	static boolean retryable(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof ApiException || cause instanceof MessageConversionException
					|| cause instanceof org.springframework.messaging.converter.MessageConversionException
					|| cause instanceof AmqpRejectAndDontRequeueException) {
				return false;
			}
		}
		return true;
	}

	private void logDeadLetter(JsonMapper mapper, Message message, Throwable failure) {
		String eventId = null;
		String paymentId = null;
		try {
			JsonNode payload = mapper.readTree(message.getBody());
			if (payload != null && payload.isObject()) {
				eventId = payload.path("eventId").asString(null);
				paymentId = payload.path("paymentId").asString(null);
			}
		}
		catch (JacksonException exception) {
			logger.warn("Cannot extract payment identifiers from malformed message: messageId={}",
					message.getMessageProperties().getMessageId());
		}
		logger.error("Payment rejected to DLQ: queue={}, eventId={}, paymentId={}, messageId={}",
				DEAD_LETTER_QUEUE, eventId, paymentId,
				message.getMessageProperties().getMessageId(), failure);
	}
}
