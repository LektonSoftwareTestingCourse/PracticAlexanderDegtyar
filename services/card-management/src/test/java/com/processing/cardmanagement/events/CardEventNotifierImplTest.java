package com.processing.cardmanagement.events;

import com.processing.cardmanagement.models.CardOutboxEventData;
import com.processing.cardmanagement.services.OutboxEventProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class CardEventNotifierImplTest {

    @Mock
    private OutboxEventProcessor outboxEventProcessor;

    @Mock
    private CardEventListener listener;

    private CardEventNotifierImpl notifier;

    @BeforeEach
    void setUp() {
        notifier = new CardEventNotifierImpl(outboxEventProcessor, List.of(listener));
    }

    @Test
    void shouldStoreOutboxEventAsPendingAndNotBypassTheOutbox() {

        CardServiceReserveEvent event = new CardServiceReserveEvent(
                "4000001234567890", "123456789012", new BigDecimal("300"));

        notifier.notifyListeners(event);

        ArgumentCaptor<CardOutboxEventData> saved = ArgumentCaptor.forClass(CardOutboxEventData.class);
        verify(outboxEventProcessor).save(saved.capture());
        assertThat(saved.getValue().event()).isEqualTo(event);
        verifyNoInteractions(listener);
    }

    @Test
    void shouldDeliverNonOutboxEventToListenersWithoutTouchingTheOutbox() {

        CardsAmountUpdatedEvent event = new CardsAmountUpdatedEvent(42);

        notifier.notifyListeners(event);

        verify(listener).onEvent(event);
        verifyNoInteractions(outboxEventProcessor);
    }
}
