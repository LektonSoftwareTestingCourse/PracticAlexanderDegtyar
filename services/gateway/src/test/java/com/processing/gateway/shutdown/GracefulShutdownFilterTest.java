package com.processing.gateway.shutdown;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.processing.gateway.metrics.GatewayMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GracefulShutdownFilterTest {

    @Mock
    private GracefulShutdownState state;

    @Mock
    private GatewayMetrics metrics;

    @Mock
    private GatewayFilterChain chain;

    private GracefulShutdownFilter filter;
    private MockServerWebExchange exchange;

    @BeforeEach
    void setUp() {
        ShutdownProperties properties = new ShutdownProperties();
        properties.setDrainPeriod(Duration.ofSeconds(30));
        filter = new GracefulShutdownFilter(state, properties, new ObjectMapper(), metrics);
        exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/transactions").build());
    }

    @Test
    void shouldPassRequestsThroughWhileGatewayIsRunning() {

        when(state.isShuttingDown()).thenReturn(false);
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(exchange);
        verifyNoInteractions(metrics);
    }

    @Test
    void shouldRejectNewRequestsWithRetryAfterWhileGatewayIsShuttingDown() {

        when(state.isShuttingDown()).thenReturn(true);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("30");
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("Gateway is shutting down, retry later")
                .contains("\"serviceName\":\"gateway\"");
        verify(chain, never()).filter(any());
        verify(metrics).recordGracefulShutdownRejected();
    }
}
