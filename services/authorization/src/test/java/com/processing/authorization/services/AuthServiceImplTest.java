package com.processing.authorization.services;

import com.processing.authorization.client.BinLookupClient;
import com.processing.authorization.client.CardManagementClient;
import com.processing.authorization.events.AuthServiceAuthorizeEvent;
import com.processing.authorization.events.AuthServiceDuplicateKeyEvent;
import com.processing.authorization.events.AuthServiceRollbackEvent;
import com.processing.authorization.events.AuthorizationEvent;
import com.processing.authorization.events.AuthorizationEventNotifier;
import com.processing.authorization.exceptions.CardNotFoundException;
import com.processing.authorization.exceptions.GetCardException;
import com.processing.authorization.exceptions.InsufficientFundsException;
import com.processing.authorization.exceptions.InternalCardManagerException;
import com.processing.authorization.exceptions.InvalidGetCardRequestException;
import com.processing.authorization.exceptions.InvalidReserveRequestException;
import com.processing.authorization.exceptions.InvalidRollbackRequestException;
import com.processing.authorization.exceptions.PaymentRequiredException;
import com.processing.authorization.exceptions.ReserveException;
import com.processing.authorization.exceptions.RollbackConflictException;
import com.processing.authorization.exceptions.RollbackFailureException;
import com.processing.authorization.exceptions.ServiceUnavailableException;
import com.processing.authorization.repositories.LimitUsageRepository;
import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackRequest;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.common.dto.cardmanagement.CardModel;
import com.processing.common.dto.cardmanagement.CardModelStatus;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    private static final String PAN = "4000001234567890";
    private static final String BIN = "400000";
    private static final String ISSUER_ID = "ISS001";
    private static final String RRN = "123456789012";
    private static final BigDecimal BALANCE = new BigDecimal("10000");
    private static final BigDecimal AMOUNT = new BigDecimal("1000");
    private static final YearMonth CARD_EXPIRY = YearMonth.of(2027, 12);
    private static final Instant TRANSMISSION_TIME = Instant.parse("2026-06-15T12:00:00Z");

    private static final Instant REQUEST_TIME = Instant.now();

    @Mock
    private LimitUsageRepository limitUsageRepository;

    @Mock
    private AuthorizationEventNotifier eventNotifier;

    @Mock
    private BinLookupClient binLookupClient;

    @Mock
    private CardManagementClient cardManagementClient;

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(
                limitUsageRepository,
                eventNotifier,
                binLookupClient,
                cardManagementClient);
    }

    @Test
    void shouldApproveAuthorizationWhenCardIsActiveAndAllChecksPass() {

        when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(1);

        AuthorizationResponse response = authService.authorize(request(AMOUNT, TRANSMISSION_TIME), REQUEST_TIME);

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_APPROVED);
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_APPROVED);
        assertThat(response.rrn()).matches("\\d{12}");
        assertThat(response.authCode()).matches("[0-9A-Z]{6}");
        assertThat(response.declineReason()).isNull();
        verify(cardManagementClient).reserve(AMOUNT, response.rrn(), PAN);
        verify(eventNotifier).notify(new AuthServiceAuthorizeEvent(PAN));
    }

    @Test
    void shouldApproveAuthorizationWhenBinLookupReturnsNothing() {

        when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.empty());
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(1);

        AuthorizationResponse response = authService.authorize(request(AMOUNT, TRANSMISSION_TIME), REQUEST_TIME);

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_APPROVED);
    }

    @Test
    void shouldCopyStanFromRequestToResponse() {

        when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(1);

        AuthorizationResponse response = authService.authorize(request(AMOUNT, TRANSMISSION_TIME), REQUEST_TIME);

        assertThat(response.stan()).isEqualTo("000001");
    }

    @ParameterizedTest(name = "{0} -> {1} / {2}")
    @MethodSource("cardManagementGetCardFailures")
    void shouldDeclineWhenGetCardFails(RuntimeException failure, String expectedReason, String expectedCode) {

        when(cardManagementClient.getCard(PAN)).thenThrow(failure);

        AuthorizationResponse response = authService.authorize(request(AMOUNT, TRANSMISSION_TIME), REQUEST_TIME);

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.declineReason()).isEqualTo(expectedReason);
        assertThat(response.responseCode()).isEqualTo(expectedCode);
        assertThat(response.rrn()).isNull();
        verifyNoInteractions(limitUsageRepository);
        verify(cardManagementClient, never()).reserve(any(), any(), any());
        verify(eventNotifier).notify(any(AuthorizationEvent.class));
    }

    static Stream<Arguments> cardManagementGetCardFailures() {
        return Stream.of(
                Arguments.of(new CardNotFoundException("no card"), "CARD_NOT_FOUND", "14"),
                Arguments.of(new InvalidGetCardRequestException("bad pan"), "CARD_NOT_FOUND", "14"),
                Arguments.of(new ServiceUnavailableException("503"), "SERVICE_UNAVAILABLE", "96"),
                Arguments.of(new InternalCardManagerException("500"), "SERVICE_UNAVAILABLE", "96"),
                Arguments.of(new ResourceAccessException("timeout", new IOException("timeout")),
                        "SERVICE_UNAVAILABLE", "96"),
                Arguments.of(new GetCardException("unexpected status"), "SERVICE_UNAVAILABLE", "96"),
                Arguments.of(new PaymentRequiredException("402"), "CARD_BLOCKED", "05"),
                Arguments.of(new IllegalStateException("boom"), "UNKNOWN_REASON", "05")
        );
    }

    @ParameterizedTest(name = "status={0}")
    @EnumSource(value = CardModelStatus.class, mode = EnumSource.Mode.EXCLUDE, names = {"ACTIVE"})
    void shouldDeclineAndMoveNoMoneyWhenCardStatusIsNotActive(CardModelStatus status) {

        when(cardManagementClient.getCard(PAN)).thenReturn(cardWithStatus(status));

        AuthorizationResponse response = authService.authorize(request(AMOUNT, TRANSMISSION_TIME), REQUEST_TIME);

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        verifyNoInteractions(limitUsageRepository);
        verify(cardManagementClient, never()).reserve(any(), any(), any());
        verify(eventNotifier).notify(any(AuthorizationEvent.class));
    }

    @ParameterizedTest(name = "status={0} -> {1} / {2}")
    @CsvSource({
            "INACTIVE, CARD_INACTIVE, 05",
            "BLOCKED,  CARD_BLOCKED,  05",
            "EXPIRED,  CARD_EXPIRED,  54"
    })
    void shouldReportReasonAndCodeMatchingCardStatus(CardModelStatus status, String reason, String code) {

        when(cardManagementClient.getCard(PAN)).thenReturn(cardWithStatus(status));

        AuthorizationResponse response = authService.authorize(request(AMOUNT, TRANSMISSION_TIME), REQUEST_TIME);

        assertThat(response.declineReason()).isEqualTo(reason);
        assertThat(response.responseCode()).isEqualTo(code);
    }

    @Test
    void shouldDeclineWithUnknownReasonWhenCardStatusIsMissing() {

        when(cardManagementClient.getCard(PAN)).thenReturn(cardWithStatus(null));

        AuthorizationResponse response = authService.authorize(request(AMOUNT, TRANSMISSION_TIME), REQUEST_TIME);

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.declineReason()).isEqualTo("UNKNOWN_REASON");
        verifyNoInteractions(limitUsageRepository);
    }

    @ParameterizedTest(name = "транзакция {0} при сроке 12/2027 -> карта действительна")
    @CsvSource({
            "2026-06-15T12:00:00Z",
            "2027-12-01T00:00:00Z",
            "2027-12-31T23:59:59Z"
    })
    void shouldApproveWhenTransmissionIsNotLaterThanLastDayOfExpiryMonth(Instant transmissionTime) {

        when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(1);

        AuthorizationResponse response = authService.authorize(request(AMOUNT, transmissionTime), REQUEST_TIME);

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_APPROVED);
    }

    @ParameterizedTest(name = "транзакция {0} при сроке 12/2027 -> 54")
    @CsvSource({
            "2028-01-01T00:00:00Z",
            "2030-05-05T10:00:00Z"
    })
    void shouldDeclineWhenTransmissionIsLaterThanExpiryMonth(Instant transmissionTime) {

        when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());

        AuthorizationResponse response = authService.authorize(request(AMOUNT, transmissionTime), REQUEST_TIME);

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_CARD_EXPIRED);
        assertThat(response.declineReason()).isEqualTo("CARD_EXPIRED");
        verifyNoInteractions(limitUsageRepository);
    }

    @ParameterizedTest(name = "amount={0} при балансе 10000 -> баланса достаточно")
    @CsvSource({"9999", "10000"})
    void shouldNotDeclineByBalanceWhenAmountDoesNotExceedBalance(BigDecimal amount) {

        when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(1);

        AuthorizationResponse response = authService.authorize(request(amount, TRANSMISSION_TIME), REQUEST_TIME);

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_APPROVED);
    }

    @Test
    void shouldDeclineWithInsufficientFundsWhenAmountExceedsBalanceByOne() {

        when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());

        AuthorizationResponse response = authService.authorize(
                request(BALANCE.add(BigDecimal.ONE), TRANSMISSION_TIME), REQUEST_TIME);

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_INSUFFICIENT_FUNDS);
        assertThat(response.declineReason()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void shouldCheckBalanceBeforeLimitsSoLimitsAreNotConsumedOnInsufficientFunds() {

        when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());

        AuthorizationResponse response = authService.authorize(
                request(new BigDecimal("500000"), TRANSMISSION_TIME), REQUEST_TIME);

        assertThat(response.declineReason()).isEqualTo("INSUFFICIENT_FUNDS");
        verifyNoInteractions(limitUsageRepository);
    }

    @Test
    void shouldDeclineWithExceedsLimitWhenRepositoryRejectsUpdate() {

        when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(0);

        AuthorizationResponse response = authService.authorize(request(AMOUNT, TRANSMISSION_TIME), REQUEST_TIME);

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_EXCEEDS_LIMIT);
        assertThat(response.declineReason()).isEqualTo("EXCEEDS_AMOUNT_LIMIT");
        verify(cardManagementClient, never()).reserve(any(), any(), any());
    }

    @ParameterizedTest(name = "первая ошибка {0}")
    @MethodSource("duplicateKeyFailures")
    void shouldRetryLimitUpdateOnceWhenDuplicateKeyOccurs(RuntimeException firstFailure) {

        when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any()))
                .thenThrow(firstFailure)
                .thenReturn(1);

        AuthorizationResponse response = authService.authorize(request(AMOUNT, TRANSMISSION_TIME), REQUEST_TIME);

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_APPROVED);
        verify(eventNotifier).notify(isA(AuthServiceDuplicateKeyEvent.class));
    }

    static Stream<RuntimeException> duplicateKeyFailures() {
        return Stream.of(
                new DuplicateKeyException("duplicate"),
                new ConstraintViolationException("duplicate", new SQLException("dup"), "uk_limit_usage")
        );
    }

    @Test
    void shouldDeclineWithDbUnavailableWhenDuplicateKeyRepeats() {

        when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("first"))
                .thenThrow(new IllegalStateException("second"));

        AuthorizationResponse response = authService.authorize(request(AMOUNT, TRANSMISSION_TIME), REQUEST_TIME);

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_SERVICE_UNAVAILABLE);
        assertThat(response.declineReason()).isEqualTo("DB_UNAVAILABLE");
        verify(cardManagementClient, never()).reserve(any(), any(), any());
    }

    @Test
    void shouldDeclineWithDbUnavailableWhenLimitRepositoryFails() {

        when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("db down"));

        AuthorizationResponse response = authService.authorize(request(AMOUNT, TRANSMISSION_TIME), REQUEST_TIME);

        assertThat(response.declineReason()).isEqualTo("DB_UNAVAILABLE");
        assertThat(response.responseCode()).isEqualTo(AuthorizationResponse.CODE_SERVICE_UNAVAILABLE);
        verify(cardManagementClient, never()).reserve(any(), any(), any());
    }

    @ParameterizedTest(name = "{0} -> {1} / {2}")
    @MethodSource("reserveFailures")
    void shouldDeclineWhenReserveFails(RuntimeException failure, String expectedReason, String expectedCode) {

        when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(1);
        doThrow(failure).when(cardManagementClient).reserve(any(), any(), any());

        AuthorizationResponse response = authService.authorize(request(AMOUNT, TRANSMISSION_TIME), REQUEST_TIME);

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.declineReason()).isEqualTo(expectedReason);
        assertThat(response.responseCode()).isEqualTo(expectedCode);
        assertThat(response.rrn()).isNull();
        assertThat(response.authCode()).isNull();
    }

    static Stream<Arguments> reserveFailures() {
        return Stream.of(
                Arguments.of(new CardNotFoundException("404"), "CARD_NOT_FOUND", "14"),
                Arguments.of(new InvalidReserveRequestException("400"), "CARD_NOT_FOUND", "14"),
                Arguments.of(new InsufficientFundsException("402"), "INSUFFICIENT_FUNDS", "51"),
                Arguments.of(new ServiceUnavailableException("503"), "SERVICE_UNAVAILABLE", "96"),
                Arguments.of(new InternalCardManagerException("500"), "SERVICE_UNAVAILABLE", "96"),
                Arguments.of(new ResourceAccessException("timeout", new IOException("timeout")),
                        "SERVICE_UNAVAILABLE", "96"),
                Arguments.of(new ReserveException("409"), "RESERVATION_FAILED", "96"),
                Arguments.of(new IllegalStateException("boom"), "UNKNOWN_REASON", "05")
        );
    }

    @ParameterizedTest(name = "репозиторий вернул {0} строк -> {1}")
    @CsvSource({"1, true", "0, false"})
    void shouldReportLimitsUpdatedOnlyWhenRepositoryChangedARow(int updatedRows, boolean expected) {

        LocalDate date = LocalDate.of(2026, 6, 15);
        CardModel card = activeCard();
        when(limitUsageRepository.upsertLimitUsage(
                PAN, date, AMOUNT, card.dailyLimit(), card.monthlyLimit())).thenReturn(updatedRows);

        boolean updated = authService.checkAndUpdateLimits(card, AMOUNT, date);

        assertThat(updated).isEqualTo(expected);
    }

    @Test
    void shouldGenerateTwelveDigitRrnBuiltFromSequenceBlock() {

        when(limitUsageRepository.fetchRrnBlock()).thenReturn(7L);

        String rrn = authService.generateRRN();

        assertThat(rrn).hasSize(12).containsOnlyDigits().endsWith("00007000");
    }

    @Test
    void shouldGenerateUniqueRrnsAcrossBlockBoundaries() {

        when(limitUsageRepository.fetchRrnBlock()).thenReturn(1L, 2L, 3L);

        Set<String> rrns = IntStream.range(0, 2500)
                .mapToObj(i -> authService.generateRRN())
                .collect(Collectors.toSet());

        assertThat(rrns).hasSize(2500);
    }

    @RepeatedTest(20)
    void shouldGenerateSixCharacterAlphanumericAuthCode() {

        String authCode = authService.generateAuthCode();

        assertThat(authCode).matches("[0-9A-Z]{6}");
    }

    @Test
    void shouldApproveRollbackAndNotifyWhenCardManagementAcceptsIt() {

        RollbackRequest request = rollbackRequest();

        RollbackResponse response = authService.rollback(request, REQUEST_TIME);

        assertThat(response.status()).isEqualTo(RollbackResponse.STATUS_APPROVED);
        assertThat(response.responseCode()).isEqualTo(RollbackResponse.CODE_SUCCESS);
        assertThat(response.rrn()).isEqualTo(RRN);
        verify(cardManagementClient).rollback(request);
        verify(eventNotifier).notify(new AuthServiceRollbackEvent(PAN));
    }

    @ParameterizedTest(name = "{0} -> {1} / {2}")
    @MethodSource("rollbackFailures")
    void shouldDeclineRollbackWhenCardManagementFails(RuntimeException failure, String expectedReason,
                                                      String expectedCode) {

        RollbackRequest request = rollbackRequest();
        doThrow(failure).when(cardManagementClient).rollback(request);

        RollbackResponse response = authService.rollback(request, REQUEST_TIME);

        assertThat(response.status()).isEqualTo(RollbackResponse.STATUS_DECLINED);
        assertThat(response.declineReason()).isEqualTo(expectedReason);
        assertThat(response.responseCode()).isEqualTo(expectedCode);
        verify(eventNotifier).notify(any(AuthorizationEvent.class));
    }

    static Stream<Arguments> rollbackFailures() {
        return Stream.of(
                Arguments.of(new CardNotFoundException("404"), "TRANSACTION_NOT_FOUND", "14"),
                Arguments.of(new InvalidRollbackRequestException("400"), "TRANSACTION_NOT_FOUND", "14"),
                Arguments.of(new ServiceUnavailableException("503"), "SERVICE_UNAVAILABLE", "96"),
                Arguments.of(new InternalCardManagerException("500"), "SERVICE_UNAVAILABLE", "96"),
                Arguments.of(new ResourceAccessException("timeout", new IOException("timeout")),
                        "SERVICE_UNAVAILABLE", "96"),
                Arguments.of(new RollbackConflictException("409"), "ALREADY_ROLLED_BACK", "05"),
                Arguments.of(new RollbackFailureException("unexpected"), "ROLLBACK_FAILED", "96"),
                Arguments.of(new IllegalStateException("boom"), "UNKNOWN_REASON", "05")
        );
    }

    private AuthorizationRequest request(BigDecimal amount, Instant transmissionTime) {
        return new AuthorizationRequest(
                "0100", "000001", PAN, "000000",
                amount, "643", transmissionTime,
                "TERM0001", "POS", "MERCH0000000002", "5045", "ACQ002", ISSUER_ID
        );
    }

    private RollbackRequest rollbackRequest() {
        return new RollbackRequest(RRN, PAN, AMOUNT);
    }

    private CardModel activeCard() {
        return cardWithStatus(CardModelStatus.ACTIVE);
    }

    private CardModel cardWithStatus(CardModelStatus status) {
        return new CardModel(
                UUID.randomUUID(), PAN, BIN, "IVAN IVANOV",
                CARD_EXPIRY, status, "643",
                new BigDecimal("100000"), new BigDecimal("500000"), BALANCE,
                ISSUER_ID, Instant.now()
        );
    }
}
