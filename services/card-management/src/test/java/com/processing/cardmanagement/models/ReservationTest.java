package com.processing.cardmanagement.models;

import com.processing.cardmanagement.exceptions.RollbackAlreadySatisfiedException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationTest {

    private static final String PAN = "4000001234567890";
    private static final String RRN = "123456789012";

    @Test
    void shouldStartInReservedStatusWithGivenData() {

        Reservation reservation = new Reservation(PAN, new BigDecimal("300"), RRN);

        assertThat(reservation.id()).isNotNull();
        assertThat(reservation.pan()).isEqualTo(PAN);
        assertThat(reservation.rrn()).isEqualTo(RRN);
        assertThat(reservation.reservationAmount()).isEqualByComparingTo("300");
        assertThat(reservation.status()).isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    void shouldCreateRollbackLinkedToTheReservation() {

        Reservation reservation = new Reservation(PAN, new BigDecimal("300"), RRN);

        ReservationRollback rollback = reservation.startRollback(new BigDecimal("300"));

        assertThat(rollback.reservationId()).isEqualTo(reservation.id());
        assertThat(rollback.pan()).isEqualTo(PAN);
        assertThat(rollback.rrn()).isEqualTo(RRN);
        assertThat(rollback.rollbackAmount()).isEqualByComparingTo("300");
    }

    @Test
    void shouldRejectSecondRollbackOfTheSameReservation() {

        Reservation reservation = new Reservation(PAN, new BigDecimal("300"), RRN);
        Reservation rolledBack = reservation.rolledBack(reservation.startRollback(new BigDecimal("300")));

        assertThatThrownBy(() -> rolledBack.startRollback(new BigDecimal("300")))
                .isInstanceOf(RollbackAlreadySatisfiedException.class)
                .hasMessageContaining(RRN);
    }

    @Test
    void shouldMoveToRolledBackStatusKeepingTheRestOfTheData() {

        Reservation reservation = new Reservation(PAN, new BigDecimal("300"), RRN);
        ReservationRollback rollback = reservation.startRollback(new BigDecimal("300"));

        Reservation rolledBack = reservation.rolledBack(rollback);

        assertThat(rolledBack.status()).isEqualTo(ReservationStatus.ROLLED_BACK);
        assertThat(rolledBack.id()).isEqualTo(reservation.id());
        assertThat(rolledBack.rrn()).isEqualTo(RRN);
        assertThat(rolledBack.reservationAmount()).isEqualByComparingTo("300");
        assertThat(reservation.status()).isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    void shouldRejectRollbackOfAnotherReservation() {

        Reservation reservation = new Reservation(PAN, new BigDecimal("300"), RRN);
        ReservationRollback foreign = new ReservationRollback(
                UUID.randomUUID(), UUID.randomUUID(), PAN, new BigDecimal("300"), RRN, Instant.now());

        assertThatThrownBy(() -> reservation.rolledBack(foreign))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("differ");
    }
}
