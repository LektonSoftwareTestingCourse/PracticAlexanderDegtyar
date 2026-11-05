package com.processing.cardmanagement.services;

import com.processing.cardmanagement.config.RabbitMQConfig;
import com.processing.cardmanagement.events.CardServiceReserveEvent;
import com.processing.cardmanagement.models.CardOutboxEventData;
import com.processing.cardmanagement.models.OutboxEventDataStatus;
import com.processing.cardmanagement.options.OutboxOptions;
import com.processing.cardmanagement.repositories.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.io.IOException;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OutboxEventProcessorImplTest {

    private static final CardServiceReserveEvent EVENT =
            new CardServiceReserveEvent("4000001234567890", "123456789012", new BigDecimal("300"));

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private RabbitTemplate rabbitTemplate;

    private OutboxEventProcessorImpl processor;

    @BeforeEach
    void setUp() {
        processor = new OutboxEventProcessorImpl(outboxRepository, rabbitTemplate, new OutboxOptions(1000, 3));
    }

    @Test
    void shouldPublishEventToCardEventsExchangeWithRoutingKeyFromEventClass() {

        CardOutboxEventData data = new CardOutboxEventData(EVENT);

        processor.processSingleEvent(data);

        verify(rabbitTemplate).convertAndSend(
                RabbitMQConfig.CARD_EVENTS_EXCHANGE, "card.CardServiceReserveEvent", EVENT);
    }

    @Test
    void shouldMarkEventProcessedAfterSuccessfulPublication() {

        CardOutboxEventData data = new CardOutboxEventData(EVENT);

        processor.processSingleEvent(data);

        CardOutboxEventData saved = savedEvent();
        assertThat(saved.status()).isEqualTo(OutboxEventDataStatus.PROCESSED);
        assertThat(saved.processedAt()).isNotNull();
        assertThat(saved.retryCount()).isZero();
        assertThat(saved.id()).isEqualTo(data.id());
    }

    @ParameterizedTest(name = "было попыток {0} -> стало {1}, статус {2}")
    @CsvSource({"0, 1, PENDING", "1, 2, PENDING", "2, 3, FAILED"})
    void shouldCountFailedAttemptAndGiveUpOnTheThird(int attemptsBefore, int attemptsAfter,
                                                      OutboxEventDataStatus expectedStatus) {

        brokerIsDown();
        CardOutboxEventData data = withAttempts(attemptsBefore);

        processor.processSingleEvent(data);

        CardOutboxEventData saved = savedEvent();
        assertThat(saved.retryCount()).isEqualTo(attemptsAfter);
        assertThat(saved.status()).isEqualTo(expectedStatus);
        assertThat(saved.lastError()).contains("connection refused");
        assertThat(saved.processedAt()).isNull();
    }

    @Test
    void shouldDelegateSavingToRepository() {

        CardOutboxEventData data = new CardOutboxEventData(EVENT);

        processor.save(data);

        verify(outboxRepository).save(data);
    }

    private void brokerIsDown() {
        doThrow(new AmqpConnectException(new IOException("connection refused")))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    private CardOutboxEventData withAttempts(int attempts) {
        CardOutboxEventData data = new CardOutboxEventData(EVENT);
        for (int i = 0; i < attempts; i++) {
            data = data.withRetry("earlier failure");
        }
        return data;
    }

    private CardOutboxEventData savedEvent() {
        ArgumentCaptor<CardOutboxEventData> captor = ArgumentCaptor.forClass(CardOutboxEventData.class);
        verify(outboxRepository).save(captor.capture());
        return captor.getValue();
    }
}
