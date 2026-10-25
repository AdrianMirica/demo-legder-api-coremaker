package ro.demo.ledger_api.messaging.outbox;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class OutboxMessagingConfiguration {
	public static final String TRANSACTION_EXCHANGE = "ledger.transactions";

	@Bean
	TopicExchange transactionExchange() {
		return new TopicExchange(TRANSACTION_EXCHANGE, true, false);
	}

}
