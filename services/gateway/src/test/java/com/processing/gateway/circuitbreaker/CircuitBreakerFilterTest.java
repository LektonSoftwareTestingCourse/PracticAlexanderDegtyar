package com.processing.gateway.circuitbreaker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.processing.gateway.downstream.DownstreamServiceResolver;
import com.processing.gateway.metrics.GatewayMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.ConnectException;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CircuitBreakerFilterTest {

    private static final String SERVICE = "switch";

    @Mock
    private DownstreamServiceResolver resolver;

    @Mock
    private InMemoryCircuitBreaker circuitBreaker;

    @Mock
    private GatewayMetrics metrics;

    @Mock
    private GatewayFilterChain chain;

    private CircuitBreakerFilter filter;
    private MockServerWebExchange exchange;

    @BeforeEach
    void setUp() {
        filter = new CircuitBreakerFilter(
                new ObjectMapper(), resolver, circuitBreaker, metrics, Duration.ofSeconds(10));
        exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/transactions").build());
    }

    @Test
    void shouldPassThroughWithoutCircuitAccountingWhenPathHasNoDownstreamService() {

        when(resolver.resolve("/api/transactions")).thenReturn(Optional.empty());
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(exchange);
        verifyNoInteractions(circuitBreaker, metrics);
    }

    @Test
    void shouldAnswerServiceUnavailableWithRetryAfterWhenCircuitIsOpen() {

        when(resolver.resolve("/api/transactions")).thenReturn(Optional.of(SERVICE));
        when(circuitBreaker.allowRequest(SERVICE)).thenReturn(false);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("10");
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("\"error\":\"SERVICE_UNAVAILABLE\"")
                .contains("switch service is temporarily unavailable")
                .contains("\"serviceName\":\"switch\"");
        verify(chain, never()).filter(any());
        verify(metrics).recordCircuitOpen(SERVICE);
    }

    @ParameterizedTest(name = "ответ downstream {0} -> успех")
    @ValueSource(ints = {200, 201, 204, 400, 401, 422})
    void shouldRecordSuccessForSuccessfulAndClientErrorResponses(int status) {

        downstreamAnswers(status);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(circuitBreaker).recordSuccess(SERVICE);
    }

    @ParameterizedTest(name = "ответ downstream {0} -> сбой")
    @ValueSource(ints = {500, 502, 503})
    void shouldRecordFailureForServerErrorResponses(int status) {

        downstreamAnswers(status);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(circuitBreaker).recordFailure(SERVICE);
    }

    @ParameterizedTest(name = "ответ downstream {0} -> ни успех, ни сбой")
    @ValueSource(ints = {404, 405})
    void shouldReleaseTrialRequestForNotFoundAndMethodNotAllowed(int status) {

        downstreamAnswers(status);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(circuitBreaker).releaseRequest(SERVICE);
        verify(circuitBreaker, never()).recordSuccess(SERVICE);
        verify(circuitBreaker, never()).recordFailure(SERVICE);
    }

    @Test
    void shouldRecordFailureWhenDownstreamCannotBeReached() {

        allowedRequest();
        when(chain.filter(exchange)).thenReturn(Mono.error(new ConnectException("refused")));

        StepVerifier.create(filter.filter(exchange, chain)).expectError(ConnectException.class).verify();

        verify(circuitBreaker).recordFailure(SERVICE);
    }

    @Test
    void shouldNotCountUnrelatedErrorAsDownstreamFailure() {

        allowedRequest();
        when(chain.filter(exchange)).thenReturn(Mono.error(new IllegalStateException("bug")));

        StepVerifier.create(filter.filter(exchange, chain)).expectError(IllegalStateException.class).verify();

        verify(circuitBreaker).releaseRequest(SERVICE);
        verify(circuitBreaker, never()).recordFailure(SERVICE);
    }

    private void allowedRequest() {
        when(resolver.resolve("/api/transactions")).thenReturn(Optional.of(SERVICE));
        when(circuitBreaker.allowRequest(SERVICE)).thenReturn(true);
    }

    private void downstreamAnswers(int status) {
        allowedRequest();
        when(chain.filter(exchange)).thenReturn(Mono.fromRunnable(
                () -> exchange.getResponse().setStatusCode(HttpStatusCode.valueOf(status))));
    }
}
