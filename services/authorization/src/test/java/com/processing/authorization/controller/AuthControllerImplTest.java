package com.processing.authorization.controller;

import com.processing.authorization.services.AuthServiceImpl;
import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackRequest;
import com.processing.common.dto.authorization.RollbackResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthControllerImplTest {

    private static final String PAN = "4000001234567890";
    private static final String RRN = "123456789012";

    private static final Instant NOW = Instant.now();

    @Mock
    private AuthServiceImpl authService;

    private AuthControllerImpl controller;

    @BeforeEach
    void setUp() {
        controller = new AuthControllerImpl(authService);
    }

    @Test
    void shouldReturnOkWithBodyWhenAuthorizationIsApproved() {

        AuthorizationRequest request = authorizationRequest();
        AuthorizationResponse approved = AuthorizationResponse.approved(request, RRN, "ABC123", NOW);
        when(authService.authorize(eq(request), any(Instant.class))).thenReturn(approved);

        ResponseEntity<AuthorizationResponse> entity = controller.authorize(request);

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(entity.getBody()).isEqualTo(approved);
    }

    @ParameterizedTest(name = "{0} -> HTTP {1}")
    @CsvSource({
            "CARD_NOT_FOUND,        404",
            "SERVICE_UNAVAILABLE,   503",
            "RESERVATION_FAILED,    503",
            "INSUFFICIENT_FUNDS,    422",
            "CARD_EXPIRED,          403",
            "CARD_BLOCKED,          403",
            "CARD_INACTIVE,         403",
            "EXCEEDS_AMOUNT_LIMIT,  400",
            "DB_UNAVAILABLE,        400",
            "UNKNOWN_REASON,        400"
    })
    void shouldMapAuthorizationDeclineReasonToHttpStatus(String reason, int expectedStatus) {

        AuthorizationRequest request = authorizationRequest();
        AuthorizationResponse declined = AuthorizationResponse.declined(request, reason, "05", NOW);
        when(authService.authorize(eq(request), any(Instant.class))).thenReturn(declined);

        ResponseEntity<AuthorizationResponse> entity = controller.authorize(request);

        assertThat(entity.getStatusCode().value()).isEqualTo(expectedStatus);
        assertThat(entity.getBody()).isEqualTo(declined);
    }

    @Test
    void shouldReturnOkWhenRollbackIsApproved() {

        RollbackRequest request = rollbackRequest();
        RollbackResponse approved = RollbackResponse.approved(request, NOW);
        when(authService.rollback(eq(request), any(Instant.class))).thenReturn(approved);

        ResponseEntity<RollbackResponse> entity = controller.rollback(request);

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(entity.getBody()).isEqualTo(approved);
    }

    @ParameterizedTest(name = "{0} -> HTTP {1}")
    @CsvSource({
            "TRANSACTION_NOT_FOUND, 404",
            "CARD_NOT_FOUND,        404",
            "ALREADY_ROLLED_BACK,   409",
            "SERVICE_UNAVAILABLE,   503",
            "ROLLBACK_FAILED,       503",
            "UNKNOWN_REASON,        400"
    })
    void shouldMapRollbackDeclineReasonToHttpStatus(String reason, int expectedStatus) {

        RollbackRequest request = rollbackRequest();
        RollbackResponse declined = RollbackResponse.declined(request, reason, "05", NOW);
        when(authService.rollback(eq(request), any(Instant.class))).thenReturn(declined);

        ResponseEntity<RollbackResponse> entity = controller.rollback(request);

        assertThat(entity.getStatusCode().value()).isEqualTo(expectedStatus);
        assertThat(entity.getBody()).isEqualTo(declined);
    }

    private AuthorizationRequest authorizationRequest() {
        return new AuthorizationRequest(
                "0100", "000001", PAN, "000000",
                new BigDecimal("1000"), "643", NOW,
                "TERM0001", "POS", "MERCH0000000002", "5045", "ACQ002", "ISS001"
        );
    }

    private RollbackRequest rollbackRequest() {
        return new RollbackRequest(RRN, PAN, new BigDecimal("1000"));
    }
}
