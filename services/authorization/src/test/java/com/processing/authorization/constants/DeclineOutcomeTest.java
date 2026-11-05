package com.processing.authorization.constants;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackRequest;
import com.processing.common.dto.authorization.RollbackResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class DeclineOutcomeTest {

    private static final Instant NOW = Instant.now();

    @ParameterizedTest(name = "{1} -> код {2}")
    @MethodSource("outcomes")
    void shouldPairEachReasonWithItsIsoResponseCode(DeclineOutcome outcome, String reason, String code) {

        assertThat(outcome.reason()).isEqualTo(reason);
        assertThat(outcome.code()).isEqualTo(code);
    }

    static Stream<Arguments> outcomes() {
        return Stream.of(
                Arguments.of(DeclineOutcome.CARD_NOT_FOUND, "CARD_NOT_FOUND", "14"),
                Arguments.of(DeclineOutcome.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "96"),
                Arguments.of(DeclineOutcome.UNKNOWN_REASON, "UNKNOWN_REASON", "05"),
                Arguments.of(DeclineOutcome.CARD_EXPIRED, "CARD_EXPIRED", "54"),
                Arguments.of(DeclineOutcome.CARD_BLOCKED, "CARD_BLOCKED", "05"),
                Arguments.of(DeclineOutcome.CARD_INACTIVE, "CARD_INACTIVE", "05"),
                Arguments.of(DeclineOutcome.EXCEEDS_AMOUNT_LIMIT, "EXCEEDS_AMOUNT_LIMIT", "61"),
                Arguments.of(DeclineOutcome.INSUFFICIENT_FUNDS, "INSUFFICIENT_FUNDS", "51"),
                Arguments.of(DeclineOutcome.RESERVATION_FAILED, "RESERVATION_FAILED", "96"),
                Arguments.of(DeclineOutcome.TRANSACTION_NOT_FOUND, "TRANSACTION_NOT_FOUND", "14"),
                Arguments.of(DeclineOutcome.ALREADY_ROLLED_BACK, "ALREADY_ROLLED_BACK", "05"),
                Arguments.of(DeclineOutcome.ROLLBACK_FAILED, "ROLLBACK_FAILED", "96"),
                Arguments.of(DeclineOutcome.DB_UNAVAILABLE, "DB_UNAVAILABLE", "96")
        );
    }

    @Test
    void shouldBuildDeclinedAuthorizationWithoutRrnAndAuthCode() {

        AuthorizationRequest request = new AuthorizationRequest(
                "0100", "000001", "4000001234567890", "000000",
                new BigDecimal("1000"), "643", NOW,
                "TERM0001", "POS", "MERCH0000000002", "5045", "ACQ002", "ISS001");

        AuthorizationResponse response = DeclineOutcome.INSUFFICIENT_FUNDS.buildAuthorization(request, NOW);

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.declineReason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(response.responseCode()).isEqualTo("51");
        assertThat(response.stan()).isEqualTo("000001");
        assertThat(response.rrn()).isNull();
        assertThat(response.authCode()).isNull();
    }

    @Test
    void shouldBuildDeclinedRollbackKeepingOriginalRrn() {

        RollbackRequest request = new RollbackRequest("123456789012", "4000001234567890", BigDecimal.TEN);

        RollbackResponse response = DeclineOutcome.ALREADY_ROLLED_BACK.buildRollback(request, NOW);

        assertThat(response.status()).isEqualTo(RollbackResponse.STATUS_DECLINED);
        assertThat(response.declineReason()).isEqualTo("ALREADY_ROLLED_BACK");
        assertThat(response.responseCode()).isEqualTo("05");
        assertThat(response.rrn()).isEqualTo("123456789012");
    }
}
