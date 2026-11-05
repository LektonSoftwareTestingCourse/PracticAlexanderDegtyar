package com.processing.service;

import com.processing.common.dto.transactionlogger.TransactionRequest;
import com.processing.common.dto.transactionlogger.TransactionStatus;
import com.processing.config.RabbitMQConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LoggerClientTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    private LoggerClient loggerClient;

    @BeforeEach
    void setUp() {
        loggerClient = new LoggerClient(rabbitTemplate);
    }

    @Test
    void shouldPublishTransactionToTransactionsExchangeAndReportSuccess() {

        TransactionRequest transaction = transaction();

        boolean published = loggerClient.log(transaction);

        assertThat(published).isTrue();
        verify(rabbitTemplate).convertAndSend(
                RabbitMQConfig.EXCHANGE, RabbitMQConfig.ROUTING_KEY, transaction);
    }

    @Test
    void shouldReportFailureInsteadOfThrowingWhenBrokerIsUnavailable() {

        doThrow(new AmqpConnectException(new IOException("connection refused")))
                .when(rabbitTemplate).convertAndSend(eq(RabbitMQConfig.EXCHANGE), eq(RabbitMQConfig.ROUTING_KEY),
                        any(TransactionRequest.class));

        boolean published = loggerClient.log(transaction());

        assertThat(published).isFalse();
    }

    private TransactionRequest transaction() {
        return new TransactionRequest(
                UUID.randomUUID(), "0100", "000001", "612300005000", "4000001234567890", "000000",
                new BigDecimal("1500"), "643", "TERM0001", "POS", "MERCH0000000002", "5045",
                "ACQ002", "ISS001", null, TransactionStatus.APPROVED, null, "ABC123", 12,
                Instant.parse("2026-06-15T12:00:00Z"), Instant.now());
    }
}
