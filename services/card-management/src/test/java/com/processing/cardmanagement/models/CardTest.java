package com.processing.cardmanagement.models;

import com.processing.cardmanagement.exceptions.InsufficientFundsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardTest {

    private static final String PAN = "4000001234567890";
    private static final String BIN = "400000";
    private static final String RRN = "123456789012";

    @ParameterizedTest(name = "резерв {0} при балансе 1000 -> допустим")
    @CsvSource({"1", "999.99", "1000"})
    void shouldStartReservationWhenAmountDoesNotExceedBalance(BigDecimal amount) {

        Card card = cardWith(CardStatus.ACTIVE, "1000");

        Reservation reservation = card.startReservation(amount, RRN);

        assertThat(reservation.pan()).isEqualTo(PAN);
        assertThat(reservation.rrn()).isEqualTo(RRN);
        assertThat(reservation.reservationAmount()).isEqualByComparingTo(amount);
        assertThat(reservation.status()).isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    void shouldRejectReservationWhenAmountExceedsBalanceByTheSmallestUnit() {

        Card card = cardWith(CardStatus.ACTIVE, "1000");

        assertThatThrownBy(() -> card.startReservation(new BigDecimal("1000.01"), RRN))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @ParameterizedTest(name = "статус {0}")
    @EnumSource(value = CardStatus.class, mode = EnumSource.Mode.EXCLUDE, names = {"ACTIVE"})
    void shouldRejectReservationWhenCardIsNotActive(CardStatus status) {

        Card card = cardWith(status, "1000");

        assertThatThrownBy(() -> card.startReservation(BigDecimal.ONE, RRN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(status.name());
    }

    @Test
    void shouldCheckStatusBeforeBalanceWhenBothAreViolated() {

        Card card = cardWith(CardStatus.BLOCKED, "10");

        assertThatThrownBy(() -> card.startReservation(new BigDecimal("500"), RRN))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldDecreaseBalanceByReservedAmountAndKeepOriginalCardUntouched() {

        Card card = cardWith(CardStatus.ACTIVE, "1000");
        Reservation reservation = card.startReservation(new BigDecimal("300"), RRN);

        Card reserved = card.withReservation(reservation);

        assertThat(reserved.availableBalance()).isEqualByComparingTo("700");
        assertThat(card.availableBalance()).isEqualByComparingTo("1000");
        assertThat(reserved.id()).isEqualTo(card.id());
    }

    @Test
    void shouldRejectReservationBelongingToAnotherCard() {

        Card card = cardWith(CardStatus.ACTIVE, "1000");
        Reservation foreign = new Reservation("4000009999999999", BigDecimal.TEN, RRN);

        assertThatThrownBy(() -> card.withReservation(foreign))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("differ");
    }

    @Test
    void shouldIncreaseBalanceByRolledBackAmount() {

        Card card = cardWith(CardStatus.ACTIVE, "700");
        ReservationRollback rollback = new ReservationRollback(
                UUID.randomUUID(), UUID.randomUUID(), PAN, new BigDecimal("300"), RRN, Instant.now());

        Card restored = card.withRollback(rollback);

        assertThat(restored.availableBalance()).isEqualByComparingTo("1000");
        assertThat(card.availableBalance()).isEqualByComparingTo("700");
    }

    @Test
    void shouldRejectRollbackBelongingToAnotherCard() {

        Card card = cardWith(CardStatus.ACTIVE, "700");
        ReservationRollback foreign = new ReservationRollback(
                UUID.randomUUID(), UUID.randomUUID(), "4000009999999999", BigDecimal.TEN, RRN, Instant.now());

        assertThatThrownBy(() -> card.withRollback(foreign)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "из статуса {0}")
    @EnumSource(value = CardStatus.class, mode = EnumSource.Mode.EXCLUDE, names = {"DELETED"})
    void shouldMarkAnyLiveCardAsDeleted(CardStatus status) {

        Card card = cardWith(status, "1000");

        Card deleted = card.deleted();

        assertThat(deleted.status()).isEqualTo(CardStatus.DELETED);
        assertThat(deleted.pan()).isEqualTo(PAN);
        assertThat(card.status()).isEqualTo(status);
    }

    @Test
    void shouldRejectDeletingAnAlreadyDeletedCard() {

        Card card = cardWith(CardStatus.DELETED, "1000");

        assertThatThrownBy(card::deleted)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already deleted");
    }

    @Test
    void shouldReplaceStatusLimitsAndBalanceAndKeepIdentity() {

        Card card = cardWith(CardStatus.ACTIVE, "1000");

        Card updated = card.withData(CardStatus.BLOCKED, new BigDecimal("500"), new BigDecimal("9000"),
                new BigDecimal("42"));

        assertThat(updated.status()).isEqualTo(CardStatus.BLOCKED);
        assertThat(updated.dailyLimit()).isEqualByComparingTo("500");
        assertThat(updated.monthlyLimit()).isEqualByComparingTo("9000");
        assertThat(updated.availableBalance()).isEqualByComparingTo("42");
        assertThat(updated.pan()).isEqualTo(PAN);
        assertThat(updated.id()).isEqualTo(card.id());
    }

    @ParameterizedTest(name = "daily={0}, monthly={1} -> допустимо")
    @CsvSource({"0, 0", "100, 100", "100, 101", "0, 1"})
    void shouldAcceptLimitsWhereMonthlyIsNotLessThanDaily(BigDecimal daily, BigDecimal monthly) {

        Card card = cardWith(CardStatus.ACTIVE, "1000");

        assertThatCode(() -> card.withData(CardStatus.ACTIVE, daily, monthly, BigDecimal.TEN))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "daily={0}, monthly={1} -> отказ")
    @CsvSource({"-1, 100", "100, -1", "-1, -1"})
    void shouldRejectNegativeLimits(BigDecimal daily, BigDecimal monthly) {

        Card card = cardWith(CardStatus.ACTIVE, "1000");

        assertThatThrownBy(() -> card.withData(CardStatus.ACTIVE, daily, monthly, BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }

    @Test
    void shouldRejectDailyLimitLargerThanMonthlyByOne() {

        Card card = cardWith(CardStatus.ACTIVE, "1000");

        assertThatThrownBy(() -> card.withData(
                CardStatus.ACTIVE, new BigDecimal("101"), new BigDecimal("100"), BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Daily limit");
    }

    @ParameterizedTest(name = "срок действия {0} лет")
    @ValueSource(ints = {1, 3, 5})
    void shouldSetExpiryDateToCurrentMonthPlusValidityPeriod(int years) {

        CardDraft draft = draft();
        YearMonth before = YearMonth.now().plusYears(years);

        Card card = Card.fromDraft(PAN, "ISS001", years, draft);

        YearMonth after = YearMonth.now().plusYears(years);
        assertThat(card.expiryDate()).isIn(before, after);
    }

    @Test
    void shouldCopyDraftFieldsAndGenerateIdentityWhenCreatingFromDraft() {

        CardDraft draft = draft();

        Card card = Card.fromDraft(PAN, "ISS001", 3, draft);

        assertThat(card.id()).isNotNull();
        assertThat(card.createdAt()).isNotNull();
        assertThat(card.pan()).isEqualTo(PAN);
        assertThat(card.issuerId()).isEqualTo("ISS001");
        assertThat(card.bin()).isEqualTo(BIN);
        assertThat(card.cardholderName()).isEqualTo("IVAN IVANOV");
        assertThat(card.status()).isEqualTo(CardStatus.ACTIVE);
        assertThat(card.currencyCode()).isEqualTo("643");
        assertThat(card.dailyLimit()).isEqualByComparingTo("1000");
        assertThat(card.monthlyLimit()).isEqualByComparingTo("30000");
        assertThat(card.availableBalance()).isEqualByComparingTo("5000");
    }

    @Test
    void shouldGiveCopyWithNewPanAndNewIdentityButSameData() {

        Card card = cardWith(CardStatus.ACTIVE, "1000");

        Card copy = card.copyWithPan("4000001111111111");

        assertThat(copy.pan()).isEqualTo("4000001111111111");
        assertThat(copy.id()).isNotEqualTo(card.id());
        assertThat(copy.availableBalance()).isEqualByComparingTo(card.availableBalance());
        assertThat(copy.bin()).isEqualTo(card.bin());
    }

    @Test
    void shouldPassStatusCheckForActiveCard() {

        Card card = cardWith(CardStatus.ACTIVE, "1");

        assertThatCode(card::checkStatusOrThrow).doesNotThrowAnyException();
    }

    private Card cardWith(CardStatus status, String balance) {
        return new Card(
                UUID.randomUUID(), PAN, BIN, "IVAN IVANOV", YearMonth.of(2027, 12), status, "643",
                new BigDecimal("1000"), new BigDecimal("30000"), new BigDecimal(balance), "ISS001");
    }

    private CardDraft draft() {
        return new CardDraft(
                BIN, "IVAN IVANOV", CardStatus.ACTIVE, "643",
                new BigDecimal("1000"), new BigDecimal("30000"), new BigDecimal("5000"));
    }
}
