package com.processing.cardmanagement.services;

import com.processing.cardmanagement.events.CardEventNotifier;
import com.processing.cardmanagement.events.CardsBatchGeneratedEvent;
import com.processing.cardmanagement.exceptions.CardGenerationLimitException;
import com.processing.cardmanagement.models.CardDraft;
import com.processing.cardmanagement.models.CardStatus;
import com.processing.cardmanagement.options.CardGeneratorOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class CardGeneratorServiceTest {

    private static final CardGeneratorOptions OPTIONS = new CardGeneratorOptions(
            new BigDecimal("1000000"), new BigDecimal("5000000"),
            new BigDecimal("500000"), new BigDecimal("3000000"),
            "643", 1000);

    @Mock
    private CardService cardService;

    @Mock
    private CardEventNotifier eventNotifier;

    @Captor
    private ArgumentCaptor<List<CardDraft>> draftsCaptor;

    private CardGeneratorService generator;

    @BeforeEach
    void setUp() {
        generator = new CardGeneratorService(cardService, OPTIONS, eventNotifier);
    }

    @Test
    void shouldRejectCountAboveConfiguredMaximum() {

        assertThatThrownBy(() -> generator.generate(1001, List.of("400000")))
                .isInstanceOf(CardGenerationLimitException.class)
                .hasMessageContaining("1000");
        verifyNoInteractions(cardService, eventNotifier);
    }

    @Test
    void shouldDistributeStatusesAs95Active3Inactive2Blocked() {

        generator.generate(100, List.of("400000"));

        verify(cardService).createCards(draftsCaptor.capture());
        assertThat(draftsCaptor.getValue()).hasSize(100);
        assertThat(draftsCaptor.getValue().stream().filter(d -> d.status() == CardStatus.ACTIVE)).hasSize(95);
        assertThat(draftsCaptor.getValue().stream().filter(d -> d.status() == CardStatus.INACTIVE)).hasSize(3);
        assertThat(draftsCaptor.getValue().stream().filter(d -> d.status() == CardStatus.BLOCKED)).hasSize(2);
    }

    @Test
    void shouldPublishStatusCountsOfTheGeneratedBatch() {

        generator.generate(100, List.of("400000"));

        verify(eventNotifier).notifyListeners(new CardsBatchGeneratedEvent(Map.of(
                CardStatus.ACTIVE, 95L, CardStatus.INACTIVE, 3L, CardStatus.BLOCKED, 2L)));
    }

    @Test
    void shouldSpreadCardsAcrossBinsInTurn() {

        generator.generate(6, List.of("400000", "400001", "400002"));

        verify(cardService).createCards(draftsCaptor.capture());
        assertThat(draftsCaptor.getValue()).extracting(CardDraft::bin)
                .containsExactly("400000", "400001", "400002", "400000", "400001", "400002");
    }

    @Test
    void shouldGenerateAmountsInsideConfiguredRangesAndMonthlyLimitAsThirtyDailyLimits() {

        generator.generate(50, List.of("400000"));

        verify(cardService).createCards(draftsCaptor.capture());
        assertThat(draftsCaptor.getValue()).allSatisfy(draft -> {
            assertThat(draft.initialBalance()).isBetween(OPTIONS.minBalance(), OPTIONS.maxBalance());
            assertThat(draft.dailyLimit()).isBetween(OPTIONS.minDailyLimit(), OPTIONS.maxDailyLimit());
            assertThat(draft.monthlyLimit()).isEqualByComparingTo(draft.dailyLimit().multiply(BigDecimal.valueOf(30)));
            assertThat(draft.currencyCode()).isEqualTo("643");
            assertThat(draft.cardholderName()).isNotBlank().isEqualTo(draft.cardholderName().toUpperCase());
        });
    }
}
