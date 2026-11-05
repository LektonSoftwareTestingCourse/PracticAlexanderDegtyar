package com.processing.controller;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.service.RouteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RouteControllerTest {

    @Mock
    private RouteService routeService;

    private RouteController controller;

    @BeforeEach
    void setUp() {
        controller = new RouteController(routeService);
    }

    @Test
    void shouldReturnOkWithServiceResponse() {

        AuthorizationRequest request = request("4000001234567890");
        AuthorizationResponse response = new AuthorizationResponse(
                "0100", "000001", "612300005000", "ABC123", "00", "APPROVED", null, 10);
        when(routeService.route(request)).thenReturn(response);

        ResponseEntity<AuthorizationResponse> entity = controller.routeTransaction(request);

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(entity.getBody()).isEqualTo(response);
    }

    @Test
    void shouldAnswerOkEvenWhenTransactionIsDeclined() {

        AuthorizationRequest request = request("4000001234567890");
        AuthorizationResponse declined = AuthorizationResponse.unknownBin("000001");
        when(routeService.route(request)).thenReturn(declined);

        ResponseEntity<AuthorizationResponse> entity = controller.routeTransaction(request);

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(entity.getBody().status()).isEqualTo("DECLINED");
    }

    @ParameterizedTest(name = "PAN \"{0}\"")
    @ValueSource(strings = {"", "1234567", "4000001234567890"})
    @NullSource
    void shouldHandleAnyPanWhenMaskingItForTheLog(String pan) {

        AuthorizationRequest request = request(pan);
        when(routeService.route(request)).thenReturn(AuthorizationResponse.unknownBin("000001"));

        ResponseEntity<AuthorizationResponse> entity = controller.routeTransaction(request);

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private AuthorizationRequest request(String pan) {
        return new AuthorizationRequest(
                "0100", "000001", pan, "000000", new BigDecimal("1500"), "643",
                Instant.parse("2026-06-15T12:00:00Z"),
                "TERM0001", "POS", "MERCH0000000002", "5045", "ACQ002", "ISS001");
    }
}
