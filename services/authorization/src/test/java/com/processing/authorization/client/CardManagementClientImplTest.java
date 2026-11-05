package com.processing.authorization.client;

import com.processing.authorization.events.AuthorizationEventNotifier;
import com.processing.authorization.events.CmsClientGetCardEvent;
import com.processing.authorization.events.CmsClientReserveEvent;
import com.processing.authorization.events.CmsClientRollbackEvent;
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
import com.processing.common.dto.authorization.RollbackRequest;
import com.processing.common.dto.cardmanagement.CardModel;
import com.processing.common.dto.cardmanagement.CardModelStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class CardManagementClientImplTest {

    private static final String CMS_URL = "http://cms:8081";
    private static final String PAN = "4000001234567890";
    private static final String RRN = "123456789012";

    @Mock
    private AuthorizationEventNotifier eventNotifier;

    private MockRestServiceServer server;
    private CardManagementClientImpl client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new CardManagementClientImpl(builder.build(), eventNotifier);
        ReflectionTestUtils.setField(client, "cmsUrl", CMS_URL);
    }

    @Test
    void shouldReturnCardAndNotifyWhenCardManagementAnswersOk() {

        server.expect(requestTo(CMS_URL + "/api/cards/" + PAN))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess(cardJson(), MediaType.APPLICATION_JSON));

        CardModel card = client.getCard(PAN);

        assertThat(card.pan()).isEqualTo(PAN);
        assertThat(card.status()).isEqualTo(CardModelStatus.ACTIVE);
        assertThat(card.expiryDate()).isEqualTo(YearMonth.of(2027, 12));
        assertThat(card.availableBalance()).isEqualByComparingTo("10000");
        verify(eventNotifier).notify(new CmsClientGetCardEvent(PAN));
        server.verify();
    }

    @ParameterizedTest(name = "GET card: HTTP {0} -> {1}")
    @MethodSource("getCardStatusMapping")
    void shouldTranslateGetCardHttpStatusToException(int status, Class<? extends Throwable> expected) {

        server.expect(requestTo(CMS_URL + "/api/cards/" + PAN)).andRespond(withStatus(HttpStatus.valueOf(status)));

        assertThatThrownBy(() -> client.getCard(PAN)).isInstanceOf(expected);
    }

    static Stream<Arguments> getCardStatusMapping() {
        return Stream.of(
                Arguments.of(500, InternalCardManagerException.class),
                Arguments.of(503, ServiceUnavailableException.class),
                Arguments.of(400, InvalidGetCardRequestException.class),
                Arguments.of(402, PaymentRequiredException.class),
                Arguments.of(404, CardNotFoundException.class),
                Arguments.of(409, GetCardException.class)
        );
    }

    @Test
    void shouldPostAmountAndRrnToReserveEndpoint() {

        server.expect(requestTo(CMS_URL + "/api/cards/" + PAN + "/reserve"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(content().json("{\"amount\":1000,\"rrn\":\"" + RRN + "\"}"))
                .andRespond(withSuccess());

        assertThatCode(() -> client.reserve(new BigDecimal("1000"), RRN, PAN)).doesNotThrowAnyException();
        verify(eventNotifier).notify(new CmsClientReserveEvent(PAN));
        server.verify();
    }

    @ParameterizedTest(name = "reserve: HTTP {0} -> {1}")
    @MethodSource("reserveStatusMapping")
    void shouldTranslateReserveHttpStatusToException(int status, Class<? extends Throwable> expected) {

        server.expect(requestTo(CMS_URL + "/api/cards/" + PAN + "/reserve"))
                .andRespond(withStatus(HttpStatus.valueOf(status)));

        assertThatThrownBy(() -> client.reserve(new BigDecimal("1000"), RRN, PAN)).isInstanceOf(expected);
    }

    static Stream<Arguments> reserveStatusMapping() {
        return Stream.of(
                Arguments.of(500, InternalCardManagerException.class),
                Arguments.of(503, ServiceUnavailableException.class),
                Arguments.of(400, InvalidReserveRequestException.class),
                Arguments.of(402, InsufficientFundsException.class),
                Arguments.of(404, CardNotFoundException.class),
                Arguments.of(409, ReserveException.class)
        );
    }

    @Test
    void shouldPostRollbackRequestToRollbackEndpoint() {

        server.expect(requestTo(CMS_URL + "/api/cards/" + PAN + "/rollback"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(content().json("{\"rrn\":\"" + RRN + "\",\"amount\":1000}"))
                .andRespond(withSuccess());

        assertThatCode(() -> client.rollback(rollbackRequest())).doesNotThrowAnyException();
        verify(eventNotifier).notify(new CmsClientRollbackEvent(PAN));
        server.verify();
    }

    @ParameterizedTest(name = "rollback: HTTP {0} -> {1}")
    @MethodSource("rollbackStatusMapping")
    void shouldTranslateRollbackHttpStatusToException(int status, Class<? extends Throwable> expected) {

        server.expect(requestTo(CMS_URL + "/api/cards/" + PAN + "/rollback"))
                .andRespond(withStatus(HttpStatus.valueOf(status)));

        assertThatThrownBy(() -> client.rollback(rollbackRequest())).isInstanceOf(expected);
    }

    static Stream<Arguments> rollbackStatusMapping() {
        return Stream.of(
                Arguments.of(500, InternalCardManagerException.class),
                Arguments.of(503, ServiceUnavailableException.class),
                Arguments.of(400, InvalidRollbackRequestException.class),
                Arguments.of(404, CardNotFoundException.class),
                Arguments.of(409, RollbackConflictException.class),
                Arguments.of(402, RollbackFailureException.class)
        );
    }

    private RollbackRequest rollbackRequest() {
        return new RollbackRequest(RRN, PAN, new BigDecimal("1000"));
    }

    private String cardJson() {
        return """
                {
                  "id": "11111111-1111-1111-1111-111111111111",
                  "pan": "%s",
                  "bin": "400000",
                  "cardholderName": "IVAN IVANOV",
                  "expiryDate": "2027-12",
                  "status": "ACTIVE",
                  "currencyCode": "643",
                  "dailyLimit": 100000,
                  "monthlyLimit": 500000,
                  "availableBalance": 10000,
                  "issuerId": "ISS001",
                  "createdAt": "2026-01-01T00:00:00Z"
                }
                """.formatted(PAN);
    }
}
