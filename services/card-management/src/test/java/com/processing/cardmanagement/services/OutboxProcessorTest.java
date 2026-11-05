package com.processing.cardmanagement.services;

import com.processing.cardmanagement.events.CardServicePatchEvent;
import com.processing.cardmanagement.models.CardOutboxEventData;
import com.processing.cardmanagement.options.OutboxOptions;
import com.processing.cardmanagement.repositories.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxProcessorTest {

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private OutboxEventProcessorImpl eventProcessor;

    private OutboxProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new OutboxProcessor(outboxRepository, eventProcessor, new OutboxOptions(1000, 3));
    }

    @Test
    void shouldPublishEveryPendingEventBelowTheRetryLimit() {

        CardOutboxEventData first = new CardOutboxEventData(new CardServicePatchEvent("4000001234567890"));
        CardOutboxEventData second = new CardOutboxEventData(new CardServicePatchEvent("4000001234567891"));
        when(outboxRepository.findPending(3)).thenReturn(List.of(first, second));

        processor.process();

        verify(eventProcessor).processSingleEvent(first);
        verify(eventProcessor).processSingleEvent(second);
    }

    @Test
    void shouldDoNothingWhenThereAreNoPendingEvents() {

        when(outboxRepository.findPending(3)).thenReturn(List.of());

        processor.process();

        verifyNoInteractions(eventProcessor);
    }
}
