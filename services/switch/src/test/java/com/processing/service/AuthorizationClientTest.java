package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.config.CircuitBreakerFactory;
import com.processing.config.RetryFactory;
import com.processing.config.SwitchProperties;
import com.processing.exception.AuthorizationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AuthorizationClientTest {

    private static final String AUTH_URL = "http://auth:8083";
    private static final String AUTHORIZE_URL = AUTH_URL + "/api/internal/authorize";
    private static final String ROLLBACK_URL = AUTH_URL + "/api/internal/rollback";
    private static final String PAN = "4000001234567890";
    private static final String RRN = "612300005000";
    private static final int MAX_ATTEMPTS = 3;

    private static final String APPROVED_JSON = """
            {"mti":"0100","stan":"000001","rrn":"612300005000","authCode":"ABC123",
             "responseCode":"00","status":"APPROVED","declineReason":null,"processingTimeMs":10}""";
    private static final String DECLINED_JSON = """
            {"mti":"0100","stan":"000001","rrn":null,"authCode":null,
             "responseCode":"51","status":"DECLINED","declineReason":"INSUFFICIENT_FUNDS","processingTimeMs":4}""";

    private MockRestServiceServer server;
    private AuthorizationClient client;

    @BeforeEach
    void setUp() {
        SwitchProperties properties = properties(new SwitchProperties.CircuitBreakerProperties(50, 10, 5, 30_000));
        client = clientFor(properties);
    }

    @Test
    void shouldReturnApprovedResponseWhenAuthorizationAnswersOk() {

        server.expect(requestTo(AUTHORIZE_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"pan\":\"" + PAN + "\",\"stan\":\"000001\",\"issuerId\":\"ISS001\"}"))
                .andRespond(withSuccess(APPROVED_JSON, MediaType.APPLICATION_JSON));

        AuthorizationResponse response = client.authorize(request());

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_APPROVED);
        assertThat(response.rrn()).isEqualTo(RRN);
        server.verify();
    }

    @Test
    void shouldReturnDeclineBodyAsResultEvenWhenHttpStatusIsNotSuccessful() {

        server.expect(requestTo(AUTHORIZE_URL))
                .andRespond(withStatus(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY)
                        .body(DECLINED_JSON).contentType(MediaType.APPLICATION_JSON));

        AuthorizationResponse response = client.authorize(request());

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_DECLINED);
        assertThat(response.declineReason()).isEqualTo("INSUFFICIENT_FUNDS");
        server.verify();
    }

    @Test
    void shouldRetryAndSucceedWhenFirstAttemptsFail() {

        server.expect(ExpectedCount.times(2), requestTo(AUTHORIZE_URL)).andRespond(withServerError());
        server.expect(requestTo(AUTHORIZE_URL))
                .andRespond(withSuccess(APPROVED_JSON, MediaType.APPLICATION_JSON));

        AuthorizationResponse response = client.authorize(request());

        assertThat(response.status()).isEqualTo(AuthorizationResponse.STATUS_APPROVED);
        server.verify();
    }

    @Test
    void shouldThrowAfterAllAttemptsAreExhausted() {

        server.expect(ExpectedCount.times(MAX_ATTEMPTS), requestTo(AUTHORIZE_URL)).andRespond(withServerError());

        assertThatThrownBy(() -> client.authorize(request()))
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("000001")
                .hasMessageContaining(MAX_ATTEMPTS + " attempts");
        server.verify();
    }

    @Test
    void shouldTreatUnexpectedStatusValueInBodyAsFailure() {

        String unexpected = APPROVED_JSON.replace("APPROVED", "PENDING");
        server.expect(ExpectedCount.times(MAX_ATTEMPTS), requestTo(AUTHORIZE_URL))
                .andRespond(withSuccess(unexpected, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.authorize(request()))
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("Invalid response from Authorization");
    }

    @Test
    void shouldStopCallingAuthorizationWhenCircuitBreakerIsOpen() {

        SwitchProperties strict = properties(new SwitchProperties.CircuitBreakerProperties(50, 2, 2, 60_000));
        AuthorizationClient strictClient = clientFor(strict);
        server.expect(ExpectedCount.times(2 * MAX_ATTEMPTS), requestTo(AUTHORIZE_URL)).andRespond(withServerError());
        assertThatThrownBy(() -> strictClient.authorize(request())).isInstanceOf(AuthorizationException.class);
        assertThatThrownBy(() -> strictClient.authorize(request())).isInstanceOf(AuthorizationException.class);

        assertThatThrownBy(() -> strictClient.authorize(request()))
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("Circuit breaker open");
        server.verify();
    }

    @Test
    void shouldPostReservationDetailsToRollbackEndpoint() {

        String body = "{\"rrn\":\"612300005000\",\"responseCode\":\"00\",\"status\":\"APPROVED\","
                + "\"declineReason\":null,\"processingTimeMs\":3}";
        server.expect(requestTo(ROLLBACK_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"rrn\":\"" + RRN + "\",\"pan\":\"" + PAN + "\",\"amount\":1500}"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        RollbackResponse response = client.rollback(request(), RRN);

        assertThat(response.status()).isEqualTo(RollbackResponse.STATUS_APPROVED);
        server.verify();
    }

    @Test
    void shouldReturnDeclinedRollbackAsIs() {

        String body = "{\"rrn\":\"612300005000\",\"responseCode\":\"05\",\"status\":\"DECLINED\","
                + "\"declineReason\":\"ALREADY_ROLLED_BACK\",\"processingTimeMs\":3}";
        server.expect(requestTo(ROLLBACK_URL))
                .andRespond(withStatus(org.springframework.http.HttpStatus.CONFLICT)
                        .body(body).contentType(MediaType.APPLICATION_JSON));

        RollbackResponse response = client.rollback(request(), RRN);

        assertThat(response.status()).isEqualTo(RollbackResponse.STATUS_DECLINED);
        assertThat(response.declineReason()).isEqualTo("ALREADY_ROLLED_BACK");
    }

    @Test
    void shouldReturnNullWhenRollbackAnswerIsNotUsable() {

        server.expect(requestTo(ROLLBACK_URL)).andRespond(withServerError());

        RollbackResponse response = client.rollback(request(), RRN);

        assertThat(response).isNull();
    }

    @Test
    void shouldReportOkWhenAuthorizationHealthEndpointAnswers() {

        server.expect(requestTo(AUTH_URL + "/health")).andRespond(withSuccess());

        assertThat(client.checkHealth()).isEqualTo("ok");
    }

    @Test
    void shouldReportDownWhenAuthorizationHealthEndpointFails() {

        server.expect(requestTo(AUTH_URL + "/health")).andRespond(withServerError());

        assertThat(client.checkHealth()).isEqualTo("down");
    }

    private AuthorizationClient clientFor(SwitchProperties properties) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new AuthorizationClient(
                properties,
                builder.build(),
                RetryFactory.authorizationRetry(properties),
                CircuitBreakerFactory.authorizationCircuitBreaker(properties));
    }

    private SwitchProperties properties(SwitchProperties.CircuitBreakerProperties circuitBreaker) {
        return new SwitchProperties(
                "1.0.0", Map.of(), AUTH_URL, "http://logger", "http://merchant",
                new SwitchProperties.HttpProperties(1000, 1000, 1000),
                new SwitchProperties.RetryProperties(MAX_ATTEMPTS, List.of(1L, 1L, 1L)),
                new SwitchProperties.CircuitBreakerSection(circuitBreaker));
    }

    private AuthorizationRequest request() {
        return new AuthorizationRequest(
                "0100", "000001", PAN, "000000",
                new BigDecimal("1500"), "643", Instant.parse("2026-06-15T12:00:00Z"),
                "TERM0001", "POS", "MERCH0000000002", "5045", "ACQ002", "ISS001");
    }
}
