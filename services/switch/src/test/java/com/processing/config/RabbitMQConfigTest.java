package com.processing.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitMQConfigTest {

    private final RabbitMQConfig config = new RabbitMQConfig();

    @Test
    void shouldDeclareDurableTopicExchangeForTransactions() {

        TopicExchange exchange = config.smpTransactionsExchange();

        assertThat(exchange.getName()).isEqualTo("smp.transactions");
        assertThat(exchange.isDurable()).isTrue();
    }

    @Test
    void shouldRouteMainQueueToDeadLetterExchangeUnderTheSameKey() {

        Queue queue = config.transactionLogQueue();

        assertThat(queue.getName()).isEqualTo("transaction-log");
        assertThat(queue.isDurable()).isTrue();
        assertThat(queue.getArguments())
                .containsEntry("x-dead-letter-exchange", "smp.transactions.dlx")
                .containsEntry("x-dead-letter-routing-key", "transaction-log");
    }

    @Test
    void shouldExpireDeadLetterQueueMessagesAfterSixtySeconds() {

        Queue dlq = config.transactionLogDlq();

        assertThat(dlq.getName()).isEqualTo("transaction-log-dlq");
        assertThat(dlq.getArguments()).containsEntry("x-message-ttl", 60_000);
    }

    @Test
    void shouldBindMainQueueByTransactionLogKey() {

        Binding binding = config.transactionLogBinding(config.smpTransactionsExchange(), config.transactionLogQueue());

        assertThat(binding.getExchange()).isEqualTo("smp.transactions");
        assertThat(binding.getDestination()).isEqualTo("transaction-log");
        assertThat(binding.getRoutingKey()).isEqualTo("transaction.log");
    }

    @Test
    void shouldBindDeadLetterQueueToDeadLetterExchange() {

        DirectExchange dlx = config.smpTransactionsDlx();

        Binding binding = config.dlqBinding(dlx, config.transactionLogDlq());

        assertThat(dlx.getName()).isEqualTo("smp.transactions.dlx");
        assertThat(binding.getExchange()).isEqualTo("smp.transactions.dlx");
        assertThat(binding.getDestination()).isEqualTo("transaction-log-dlq");
        assertThat(binding.getRoutingKey()).isEqualTo("transaction-log");
    }
}
