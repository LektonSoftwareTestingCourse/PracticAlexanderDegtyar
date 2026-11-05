package com.processing.cardmanagement.services;

import com.processing.cardmanagement.events.CardServiceCreationEvent;
import com.processing.cardmanagement.events.CardServiceDeletionEvent;
import com.processing.cardmanagement.events.CardServicePatchEvent;
import com.processing.cardmanagement.events.CardServiceReserveEvent;
import com.processing.cardmanagement.events.CardServiceRollbackEvent;
import com.processing.cardmanagement.events.CardsAmountUpdatedEvent;
import com.processing.cardmanagement.events.CardEventNotifier;
import com.processing.cardmanagement.exceptions.BinNotFoundException;
import com.processing.cardmanagement.exceptions.CardNotFoundException;
import com.processing.cardmanagement.exceptions.InsufficientFundsException;
import com.processing.cardmanagement.exceptions.MassiveCardCreationCollisionException;
import com.processing.cardmanagement.exceptions.OutOfRetriesException;
import com.processing.cardmanagement.exceptions.PanCollisionException;
import com.processing.cardmanagement.exceptions.ReservationAlreadyExistsException;
import com.processing.cardmanagement.exceptions.ReservationNotFoundException;
import com.processing.cardmanagement.exceptions.RollbackAlreadySatisfiedException;
import com.processing.cardmanagement.exceptions.TooLargeLimitException;
import com.processing.cardmanagement.models.Card;
import com.processing.cardmanagement.models.CardDraft;
import com.processing.cardmanagement.models.CardStatus;
import com.processing.cardmanagement.models.Reservation;
import com.processing.cardmanagement.models.ReservationStatus;
import com.processing.cardmanagement.options.CardServiceDefaults;
import com.processing.cardmanagement.options.CardServiceSettings;
import com.processing.cardmanagement.repositories.CardRepository;
import com.processing.cardmanagement.repositories.ReservationRepository;
import com.processing.cardmanagement.repositories.ReservationRollbackRepository;
import com.processing.cardmanagement.services.retries.RetryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CardServiceImplTest {

    private static final String PAN = "4000001234567890";
    private static final String BIN = "400000";
    private static final String RRN = "123456789012";
    private static final String ISSUER_ID = "ISS001";

    private static final CardServiceSettings SETTINGS = new CardServiceSettings() {
        @Override
        public int cardValidityPeriod() {
            return 3;
        }

        @Override
        public int maxPageLimit() {
            return 100;
        }

        @Override
        public int maxCardCreationRetries() {
            return 3;
        }
    };

    private static final CardServiceDefaults DEFAULTS = new CardServiceDefaults() {
        @Override
        public int pageLimit() {
            return 50;
        }

        @Override
        public long pageOffset() {
            return 0;
        }

        @Override
        public String currencyCode() {
            return "643";
        }

        @Override
        public BigDecimal dailyLimit() {
            return new BigDecimal("1000");
        }

        @Override
        public BigDecimal monthlyLimit() {
            return new BigDecimal("30000");
        }

        @Override
        public BigDecimal balance() {
            return new BigDecimal("5000");
        }
    };

    @Mock
    private CardRepository cardRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReservationRollbackRepository reservationRollbackRepository;

    @Mock
    private PanGenerator panGenerator;

    @Mock
    private CardEventNotifier eventNotifier;

    @Mock
    private BinIssuerService binIssuerService;

    private CardServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CardServiceImpl(
                cardRepository,
                reservationRepository,
                reservationRollbackRepository,
                SETTINGS,
                DEFAULTS,
                panGenerator,
                eventNotifier,
                binIssuerService,
                new RetryServiceImpl(),
                new DirectTransactionRunner());
    }

    @Test
    void shouldCreateActiveCardWithGeneratedPanAndIssuerFromBin() {

        when(binIssuerService.getIssuerId(BIN)).thenReturn(ISSUER_ID);
        when(panGenerator.generatePan(BIN)).thenReturn(PAN);
        when(cardRepository.create(any(Card.class))).thenAnswer(call -> call.getArgument(0));

        Card card = service.createCard(BIN, "IVAN IVANOV", "643",
                new BigDecimal("1000"), new BigDecimal("30000"), new BigDecimal("5000"));

        assertThat(card.pan()).isEqualTo(PAN);
        assertThat(card.status()).isEqualTo(CardStatus.ACTIVE);
        assertThat(card.issuerId()).isEqualTo(ISSUER_ID);
        assertThat(card.bin()).isEqualTo(BIN);
        assertThat(card.availableBalance()).isEqualByComparingTo("5000");
        assertThat(card.expiryDate()).isAfterOrEqualTo(YearMonth.now().plusYears(3));
        verify(eventNotifier).notifyListeners(new CardServiceCreationEvent(1));
    }

    @Test
    void shouldNotCreateAnythingWhenBinIsNotRegistered() {

        when(binIssuerService.getIssuerId("999999")).thenThrow(new BinNotFoundException("999999"));

        assertThatThrownBy(() -> service.createCard("999999", "IVAN IVANOV", "643",
                BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN))
                .isInstanceOf(BinNotFoundException.class);
        verifyNoInteractions(cardRepository, eventNotifier);
    }

    @Test
    void shouldRetryWithFreshPanWhenGeneratedPanCollides() {

        when(binIssuerService.getIssuerId(BIN)).thenReturn(ISSUER_ID);
        when(panGenerator.generatePan(BIN)).thenReturn("4000001111111111", "4000002222222222");
        when(cardRepository.create(any(Card.class)))
                .thenThrow(new PanCollisionException("4000001111111111"))
                .thenAnswer(call -> call.getArgument(0));

        Card card = service.createCard(BIN, "IVAN IVANOV", "643",
                BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN);

        assertThat(card.pan()).isEqualTo("4000002222222222");
    }

    @Test
    void shouldGiveUpAndPublishNothingWhenPanCollisionsDoNotStop() {

        when(binIssuerService.getIssuerId(BIN)).thenReturn(ISSUER_ID);
        when(panGenerator.generatePan(BIN)).thenReturn(PAN);
        when(cardRepository.create(any(Card.class))).thenThrow(new PanCollisionException(PAN));

        assertThatThrownBy(() -> service.createCard(BIN, "IVAN IVANOV", "643",
                BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN))
                .isInstanceOf(OutOfRetriesException.class);
        verifyNoInteractions(eventNotifier);
    }

    @Test
    void shouldSaveWholeBatchAtOnceAndReportItsSize() {

        when(binIssuerService.getIssuerId(BIN)).thenReturn(ISSUER_ID);
        when(panGenerator.generatePan(BIN)).thenReturn("4000001111111111", "4000002222222222");
        when(cardRepository.createAll(any())).thenAnswer(call -> call.getArgument(0));

        List<Card> saved = service.createCards(List.of(draft(), draft()));

        assertThat(saved).extracting(Card::pan).containsExactly("4000001111111111", "4000002222222222");
        verify(eventNotifier).notifyListeners(new CardServiceCreationEvent(2));
    }

    @Test
    void shouldFallBackToSavingCardsOneByOneWithNewPansWhenBatchCollides() {

        when(binIssuerService.getIssuerId(BIN)).thenReturn(ISSUER_ID);
        when(panGenerator.generatePan(BIN)).thenReturn(
                "4000000000000001", "4000000000000002", "4000000000000003", "4000000000000004");
        when(cardRepository.createAll(any())).thenThrow(new MassiveCardCreationCollisionException());
        when(cardRepository.create(any(Card.class))).thenAnswer(call -> call.getArgument(0));

        List<Card> saved = service.createCards(List.of(draft(), draft()));

        assertThat(saved).extracting(Card::pan).containsExactly("4000000000000003", "4000000000000004");
        verify(eventNotifier).notifyListeners(new CardServiceCreationEvent(2));
    }

    @Test
    void shouldReturnCardFoundByPan() {

        Card card = card(CardStatus.ACTIVE, "1000");
        when(cardRepository.findByPan(PAN)).thenReturn(Optional.of(card));

        assertThat(service.getCard(PAN)).isEqualTo(card);
    }

    @Test
    void shouldFailWithCardNotFoundWhenPanIsUnknown() {

        when(cardRepository.findByPan(PAN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCard(PAN)).isInstanceOf(CardNotFoundException.class);
    }

    @Test
    void shouldApplyDefaultPageLimitAndOffsetWhenNotSpecified() {

        List<Card> page = List.of(card(CardStatus.ACTIVE, "1"));
        when(cardRepository.findCards(50, 0L, null, null, null, null, null)).thenReturn(page);

        List<Card> result = service.getCards(null, null, null, null, null, null, null);

        assertThat(result).isEqualTo(page);
    }

    @Test
    void shouldPassExplicitFiltersToRepository() {

        List<Card> page = List.of(card(CardStatus.BLOCKED, "1"));
        when(cardRepository.findCards(10, 20L, CardStatus.BLOCKED, BIN, ISSUER_ID, null, null)).thenReturn(page);

        List<Card> result = service.getCards(10, 20L, CardStatus.BLOCKED, BIN, ISSUER_ID, null, null);

        assertThat(result).isEqualTo(page);
    }

    @Test
    void shouldAllowPageLimitEqualToTheMaximum() {

        when(cardRepository.findCards(100, 0L, null, null, null, null, null)).thenReturn(List.of());

        List<Card> result = service.getCards(100, null, null, null, null, null, null);

        assertThat(result).isEmpty();
    }

    @Test
    void shouldRejectPageLimitLargerThanTheMaximumByOne() {

        assertThatThrownBy(() -> service.getCards(101, null, null, null, null, null, null))
                .isInstanceOf(TooLargeLimitException.class);
        verifyNoInteractions(cardRepository);
    }

    @Test
    void shouldCountAllCardsAndPublishTheValue() {

        when(cardRepository.countAllCards()).thenReturn(42L);

        long count = service.countAllCardsAndUpdate();

        assertThat(count).isEqualTo(42L);
        verify(eventNotifier).notifyListeners(new CardsAmountUpdatedEvent(42L));
    }

    @Test
    void shouldCountCardsByFilter() {

        when(cardRepository.countCardsFiltered(CardStatus.ACTIVE, BIN, null, null, null)).thenReturn(7L);

        assertThat(service.countCardsFiltered(CardStatus.ACTIVE, BIN, null, null, null)).isEqualTo(7L);
    }

    @Test
    void shouldChangeOnlyStatusWhenOtherFieldsAreNotProvided() {

        Card card = card(CardStatus.ACTIVE, "5000");
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(card));
        when(cardRepository.update(any(Card.class))).thenAnswer(call -> call.getArgument(0));

        Card patched = service.patchCard(PAN, CardStatus.BLOCKED, null, null, null);

        assertThat(patched.status()).isEqualTo(CardStatus.BLOCKED);
        assertThat(patched.dailyLimit()).isEqualByComparingTo(card.dailyLimit());
        assertThat(patched.monthlyLimit()).isEqualByComparingTo(card.monthlyLimit());
        assertThat(patched.availableBalance()).isEqualByComparingTo(card.availableBalance());
        verify(eventNotifier).notifyListeners(new CardServicePatchEvent(PAN));
    }

    @Test
    void shouldChangeOnlyProvidedLimitsAndKeepStatus() {

        Card card = card(CardStatus.ACTIVE, "5000");
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(card));
        when(cardRepository.update(any(Card.class))).thenAnswer(call -> call.getArgument(0));

        Card patched = service.patchCard(PAN, null, new BigDecimal("200"), new BigDecimal("6000"), null);

        assertThat(patched.status()).isEqualTo(CardStatus.ACTIVE);
        assertThat(patched.dailyLimit()).isEqualByComparingTo("200");
        assertThat(patched.monthlyLimit()).isEqualByComparingTo("6000");
        assertThat(patched.availableBalance()).isEqualByComparingTo("5000");
    }

    @Test
    void shouldRejectPatchWhenCardDoesNotExist() {

        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.patchCard(PAN, CardStatus.BLOCKED, null, null, null))
                .isInstanceOf(CardNotFoundException.class);
        verify(cardRepository, never()).update(any());
        verifyNoInteractions(eventNotifier);
    }

    @Test
    void shouldRejectPatchWhenNewDailyLimitExceedsMonthlyLimit() {

        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(card(CardStatus.ACTIVE, "5000")));

        assertThatThrownBy(() -> service.patchCard(PAN, null, new BigDecimal("40000"), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(cardRepository, never()).update(any());
    }

    @Test
    void shouldSoftDeleteCardAndPublishDeletionEvent() {

        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(card(CardStatus.ACTIVE, "5000")));

        service.deleteCard(PAN);

        ArgumentCaptor<Card> saved = ArgumentCaptor.forClass(Card.class);
        verify(cardRepository).update(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(CardStatus.DELETED);
        verify(eventNotifier).notifyListeners(new CardServiceDeletionEvent(PAN));
    }

    @Test
    void shouldFailWithCardNotFoundWhenDeletingUnknownCard() {

        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteCard(PAN)).isInstanceOf(CardNotFoundException.class);
        verifyNoInteractions(eventNotifier);
    }

    @Test
    void shouldReserveAmountAndDecreaseBalance() {

        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(card(CardStatus.ACTIVE, "1000")));
        when(reservationRepository.isUnique(RRN, PAN)).thenReturn(true);
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(call -> call.getArgument(0));
        when(cardRepository.update(any(Card.class))).thenAnswer(call -> call.getArgument(0));

        Card reserved = service.reserve(PAN, new BigDecimal("300"), RRN);

        assertThat(reserved.availableBalance()).isEqualByComparingTo("700");
        ArgumentCaptor<Reservation> saved = ArgumentCaptor.forClass(Reservation.class);
        verify(reservationRepository).save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(saved.getValue().rrn()).isEqualTo(RRN);
        verify(eventNotifier).notifyListeners(new CardServiceReserveEvent(PAN, RRN, new BigDecimal("300")));
    }

    @Test
    void shouldRejectReservationWhenFundsAreInsufficientAndSaveNothing() {

        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(card(CardStatus.ACTIVE, "100")));

        assertThatThrownBy(() -> service.reserve(PAN, new BigDecimal("101"), RRN))
                .isInstanceOf(InsufficientFundsException.class);
        verifyNoInteractions(reservationRepository, eventNotifier);
        verify(cardRepository, never()).update(any());
    }

    @Test
    void shouldRejectReservationWhenCardIsBlocked() {

        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(card(CardStatus.BLOCKED, "1000")));

        assertThatThrownBy(() -> service.reserve(PAN, BigDecimal.ONE, RRN))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(reservationRepository, eventNotifier);
    }

    @Test
    void shouldRejectSecondReservationWithTheSameRrnAndPan() {

        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(card(CardStatus.ACTIVE, "1000")));
        when(reservationRepository.isUnique(RRN, PAN)).thenReturn(false);

        assertThatThrownBy(() -> service.reserve(PAN, new BigDecimal("300"), RRN))
                .isInstanceOf(ReservationAlreadyExistsException.class);
        verify(reservationRepository, never()).save(any());
        verify(cardRepository, never()).update(any());
    }

    @Test
    void shouldFailWithCardNotFoundWhenReservingOnUnknownCard() {

        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reserve(PAN, BigDecimal.ONE, RRN))
                .isInstanceOf(CardNotFoundException.class);
    }

    @Test
    void shouldRollBackReservationAndRestoreBalance() {

        Reservation reservation = new Reservation(PAN, new BigDecimal("300"), RRN);
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(card(CardStatus.ACTIVE, "700")));
        when(reservationRepository.findByRrnAndPanForUpdate(RRN, PAN)).thenReturn(Optional.of(reservation));
        when(reservationRollbackRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(call -> call.getArgument(0));
        when(cardRepository.update(any(Card.class))).thenAnswer(call -> call.getArgument(0));

        Card restored = service.rollback(PAN, new BigDecimal("300"), RRN);

        assertThat(restored.availableBalance()).isEqualByComparingTo("1000");
        ArgumentCaptor<Reservation> saved = ArgumentCaptor.forClass(Reservation.class);
        verify(reservationRepository).save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(ReservationStatus.ROLLED_BACK);
        verify(eventNotifier).notifyListeners(new CardServiceRollbackEvent(PAN, RRN, new BigDecimal("300")));
    }

    @Test
    void shouldFailWhenRollingBackUnknownReservation() {

        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(card(CardStatus.ACTIVE, "700")));
        when(reservationRepository.findByRrnAndPanForUpdate(RRN, PAN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rollback(PAN, new BigDecimal("300"), RRN))
                .isInstanceOf(ReservationNotFoundException.class);
        verifyNoInteractions(reservationRollbackRepository, eventNotifier);
    }

    @Test
    void shouldRejectRollbackOfAlreadyRolledBackReservationAndLeaveBalanceUntouched() {

        Reservation reserved = new Reservation(PAN, new BigDecimal("300"), RRN);
        Reservation alreadyRolledBack = reserved.rolledBack(reserved.startRollback(new BigDecimal("300")));
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(card(CardStatus.ACTIVE, "1000")));
        when(reservationRepository.findByRrnAndPanForUpdate(RRN, PAN)).thenReturn(Optional.of(alreadyRolledBack));

        assertThatThrownBy(() -> service.rollback(PAN, new BigDecimal("300"), RRN))
                .isInstanceOf(RollbackAlreadySatisfiedException.class);
        verify(cardRepository, never()).update(any());
        verifyNoInteractions(reservationRollbackRepository, eventNotifier);
    }

    @Test
    void shouldUpdateStatusOfAllCardsFoundByBins() {

        when(cardRepository.findCardsByBinsForUpdate(List.of(BIN)))
                .thenReturn(List.of(card(CardStatus.ACTIVE, "1"), card(CardStatus.INACTIVE, "2")));

        int updated = service.bulkUpdateStatus(List.of(BIN), null, CardStatus.BLOCKED);

        assertThat(updated).isEqualTo(2);
        ArgumentCaptor<Card> saved = ArgumentCaptor.forClass(Card.class);
        verify(cardRepository, times(2)).update(saved.capture());
        assertThat(saved.getAllValues()).extracting(Card::status).containsOnly(CardStatus.BLOCKED);
    }

    @Test
    void shouldUpdateStatusOfAllCardsFoundByPans() {

        when(cardRepository.findCardsByPansForUpdate(List.of(PAN))).thenReturn(List.of(card(CardStatus.ACTIVE, "1")));

        int updated = service.bulkUpdateStatus(null, List.of(PAN), CardStatus.INACTIVE);

        assertThat(updated).isEqualTo(1);
        verify(cardRepository).update(any(Card.class));
    }

    @Test
    void shouldRequireExactlyOneOfBinsOrPans() {

        assertThatThrownBy(() -> service.bulkUpdateStatus(null, null, CardStatus.BLOCKED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Either");
        assertThatThrownBy(() -> service.bulkUpdateStatus(List.of(BIN), List.of(PAN), CardStatus.BLOCKED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only one");
        verifyNoInteractions(cardRepository);
    }

    private Card card(CardStatus status, String balance) {
        return new Card(
                UUID.randomUUID(), PAN, BIN, "IVAN IVANOV", YearMonth.of(2027, 12), status, "643",
                new BigDecimal("1000"), new BigDecimal("30000"), new BigDecimal(balance), ISSUER_ID);
    }

    private CardDraft draft() {
        return new CardDraft(
                BIN, "IVAN IVANOV", CardStatus.ACTIVE, "643",
                new BigDecimal("1000"), new BigDecimal("30000"), new BigDecimal("5000"));
    }

    private static final class DirectTransactionRunner implements TransactionRunner {
        @Override
        public void run(Runnable operation) {
            operation.run();
        }

        @Override
        public <T> T runSupplier(Supplier<T> operation) {
            return operation.get();
        }

        @Override
        public <T> T runSupplierWithNestedQuery(Function<TransactionRunner, T> supplier) {
            return supplier.apply(this);
        }
    }
}
