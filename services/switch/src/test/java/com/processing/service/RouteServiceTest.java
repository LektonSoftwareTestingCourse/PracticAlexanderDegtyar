package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackRequest;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.common.dto.transactionlogger.TransactionRequest;
import com.processing.common.dto.transactionlogger.TransactionStatus;
import com.processing.exception.AuthorizationException;
import com.processing.exception.UnknownBinException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RouteServiceTest {

    private static final String PAN = "4000001234567890";
    private static final String ISSUER_ID = "ISS001";
    private static final String RRN = "612300005000";
    private static final BigDecimal AMOUNT = new BigDecimal("1500");
    private static final BigDecimal FEE = new BigDecimal("12.50");
    private static final Instant TRANSMISSION_TIME = Instant.parse("2026-06-15T12:00:00Z");

    @Mock
    private RoutingService routingService;

    @Mock
    private AuthorizationClient authorizationClient;

    @Mock
    private AcquiringFeeClient acquiringFeeClient;

    @Mock
    private LoggerClient loggerClient;

    private RouteService routeService;

    @BeforeEach
    void setUp() {
        routeService = new RouteService(routingService, authorizationClient, acquiringFeeClient, loggerClient);
    }

    @Test
    void shouldReturnApprovalAndPublishTransactionWhenAuthorizationApproves() {

        AuthorizationResponse approved = approvedResponse();
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any())).thenReturn(approved);
        when(acquiringFeeClient.fetchAcquiringFee(any(), anyString(), anyString(), anyString(), any()))
                .thenReturn(FEE);
        when(loggerClient.log(any())).thenReturn(true);

        AuthorizationResponse response = routeService.route(request(PAN));

        assertThat(response).isEqualTo(approved);
        TransactionRequest published = publishedTransaction();
        assertThat(published.status()).isEqualTo(TransactionStatus.APPROVED);
        assertThat(published.rrn()).isEqualTo(RRN);
        assertThat(published.authCode()).isEqualTo("ABC123");
        assertThat(published.issuerId()).isEqualTo(ISSUER_ID);
        assertThat(published.acquiringFee()).isEqualByComparingTo(FEE);
        assertThat(published.pan()).isEqualTo(PAN);
        assertThat(published.amount()).isEqualByComparingTo(AMOUNT);
        verify(authorizationClient, never()).rollback(any(), anyString());
    }

    @Test
    void shouldSendNormalizedRequestWithIssuerIdToAuthorization() {

        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any())).thenReturn(approvedResponse());
        when(loggerClient.log(any())).thenReturn(true);

        routeService.route(request(PAN));

        ArgumentCaptor<AuthorizationRequest> sent = ArgumentCaptor.forClass(AuthorizationRequest.class);
        verify(authorizationClient).authorize(sent.capture());
        assertThat(sent.getValue().issuerId()).isEqualTo(ISSUER_ID);
        assertThat(sent.getValue().terminalId()).isEqualTo("TERM1000");
        assertThat(sent.getValue().merchantId()).isEqualTo("MERCH2000000000");
    }

    @Test
    void shouldPublishTransactionWithoutFeeWhenMerchantAcquirerIsUnavailable() {

        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any())).thenReturn(approvedResponse());
        when(acquiringFeeClient.fetchAcquiringFee(any(), anyString(), anyString(), anyString(), any()))
                .thenReturn(null);
        when(loggerClient.log(any())).thenReturn(true);

        AuthorizationResponse response = routeService.route(request(PAN));

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_APPROVED);
        assertThat(publishedTransaction().acquiringFee()).isNull();
    }

    @Test
    void shouldPublishZeroProcessingTimeWhenAuthorizationDidNotReportIt() {

        AuthorizationResponse withoutTime = new AuthorizationResponse(
                "0100", "000001", RRN, "ABC123", "00", "APPROVED", null, null);
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any())).thenReturn(withoutTime);
        when(loggerClient.log(any())).thenReturn(true);

        routeService.route(request(PAN));

        assertThat(publishedTransaction().processingTimeMs()).isZero();
    }

    @Test
    void shouldPassThroughAuthorizationDeclineAndStillPublishIt() {

        AuthorizationResponse declined = new AuthorizationResponse(
                "0100", "000001", null, null, "51", "DECLINED", "INSUFFICIENT_FUNDS", 7);
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any())).thenReturn(declined);
        when(loggerClient.log(any())).thenReturn(true);

        AuthorizationResponse response = routeService.route(request(PAN));

        assertThat(response).isEqualTo(declined);
        TransactionRequest published = publishedTransaction();
        assertThat(published.status()).isEqualTo(TransactionStatus.DECLINED);
        assertThat(published.declineReason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(published.rrn()).isNull();
        verify(authorizationClient, never()).rollback(any(), anyString());
    }

    @Test
    void shouldDeclineWithCardNotFoundAndSkipAuthorizationWhenBinIsUnknown() {

        when(routingService.getIssuerIdByPan(PAN)).thenThrow(new UnknownBinException("400000"));
        when(loggerClient.log(any())).thenReturn(true);

        AuthorizationResponse response = routeService.route(request(PAN));

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_CARD_NOT_FOUND);
        assertThat(response.declineReason()).isEqualTo("CARD_NOT_FOUND");
        verifyNoInteractions(authorizationClient, acquiringFeeClient);
        assertThat(publishedTransaction().status()).isEqualTo(TransactionStatus.DECLINED);
    }

    @ParameterizedTest(name = "PAN \"{0}\"")
    @ValueSource(strings = {"", "123", "40000"})
    @NullSource
    void shouldDeclineWhenPanIsTooShortToContainBin(String shortPan) {

        when(routingService.getIssuerIdByPan(shortPan)).thenThrow(new UnknownBinException("null-or-short"));
        when(loggerClient.log(any())).thenReturn(true);

        AuthorizationResponse response = routeService.route(request(shortPan));

        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_CARD_NOT_FOUND);
        verifyNoInteractions(authorizationClient);
    }

    @Test
    void shouldDeclineWithServiceUnavailableWhenAuthorizationCannotBeReached() {

        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any()))
                .thenThrow(new AuthorizationException("000001", 3, "Connection refused"));
        when(loggerClient.log(any())).thenReturn(true);

        AuthorizationResponse response = routeService.route(request(PAN));

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_DECLINED_GENERAL);
        assertThat(response.declineReason()).isEqualTo("Authorization service unavailable");
        verifyNoInteractions(acquiringFeeClient);
        assertThat(publishedTransaction().issuerId()).isEqualTo(ISSUER_ID);
    }

    @Test
    void shouldRollBackReservationAndAnswerSystemErrorWhenApprovedTransactionIsNotPublished() {

        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any())).thenReturn(approvedResponse());
        when(loggerClient.log(any())).thenReturn(false);
        when(authorizationClient.rollback(any(), eq(RRN)))
                .thenReturn(RollbackResponse.approved(new RollbackRequest(RRN, PAN, AMOUNT), Instant.now()));

        AuthorizationResponse response = routeService.route(request(PAN));

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_SERVICE_UNAVAILABLE);
        assertThat(response.declineReason()).isEqualTo("System error");
        assertThat(response.rrn()).isNull();
        verify(authorizationClient).rollback(any(AuthorizationRequest.class), eq(RRN));
    }

    @ParameterizedTest(name = "результат отката: {0}")
    @MethodSource("rollbackOutcomes")
    void shouldAnswerSystemErrorRegardlessOfRollbackOutcome(RollbackResponse rollbackOutcome) {

        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any())).thenReturn(approvedResponse());
        when(loggerClient.log(any())).thenReturn(false);
        when(authorizationClient.rollback(any(), eq(RRN))).thenReturn(rollbackOutcome);

        AuthorizationResponse response = routeService.route(request(PAN));

        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_SERVICE_UNAVAILABLE);
        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
    }

    static Stream<RollbackResponse> rollbackOutcomes() {
        RollbackRequest request = new RollbackRequest(RRN, PAN, AMOUNT);
        return Stream.of(
                null,
                RollbackResponse.approved(request, Instant.now()),
                RollbackResponse.declined(request, "ALREADY_ROLLED_BACK", "05", Instant.now())
        );
    }

    @Test
    void shouldReturnDeclineAndNotRollBackWhenDeclinedTransactionIsNotPublished() {

        AuthorizationResponse declined = new AuthorizationResponse(
                "0100", "000001", null, null, "61", "DECLINED", "EXCEEDS_AMOUNT_LIMIT", 5);
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(any())).thenReturn(declined);
        when(loggerClient.log(any())).thenReturn(false);

        AuthorizationResponse response = routeService.route(request(PAN));

        assertThat(response).isEqualTo(declined);
        verify(authorizationClient, never()).rollback(any(), anyString());
    }

    private TransactionRequest publishedTransaction() {
        ArgumentCaptor<TransactionRequest> captor = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(loggerClient).log(captor.capture());
        return captor.getValue();
    }

    private AuthorizationResponse approvedResponse() {
        return new AuthorizationResponse("0100", "000001", RRN, "ABC123", "00", "APPROVED", null, 15);
    }

    private AuthorizationRequest request(String pan) {
        return new AuthorizationRequest(
                "0100", "000001", pan, "000000",
                AMOUNT, "643", TRANSMISSION_TIME,
                "TERM1", "POS", "MERCH2", "5045", "ACQ002", null);
    }
}
