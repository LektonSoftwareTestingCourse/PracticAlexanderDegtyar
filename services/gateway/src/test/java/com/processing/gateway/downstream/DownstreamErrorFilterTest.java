package com.processing.gateway.downstream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.processing.gateway.metrics.GatewayMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.ConnectException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DownstreamErrorFilterTest {

    @Mock
    private DownstreamServiceResolver resolver;

    @Mock
    private GatewayMetrics metrics;

    @Mock
    private GatewayFilterChain chain;

    private DownstreamErrorFilter filter;
    private MockServerWebExchange exchange;

    @BeforeEach
    void setUp() {
        filter = new DownstreamErrorFilter(new ObjectMapper(), resolver, metrics);
        exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/cards/1").build());
    }

    @Test
    void shouldAnswerServiceUnavailableWhenKnownDownstreamIsUnreachable() {

        when(resolver.resolve("/api/cards/1")).thenReturn(Optional.of("cardManagement"));
        when(chain.filter(exchange)).thenReturn(Mono.error(new ConnectException("refused")));

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("CardManagement service is temporarily unavailable")
                .contains("\"serviceName\":\"cardManagement\"");
        verify(metrics).recordDownstreamUnavailable("cardManagement");
    }

    @Test
    void shouldPropagateErrorsThatAreNotConnectivityFailures() {

        when(resolver.resolve("/api/cards/1")).thenReturn(Optional.of("cardManagement"));
        when(chain.filter(exchange)).thenReturn(Mono.error(new IllegalStateException("bug")));

        StepVerifier.create(filter.filter(exchange, chain)).expectError(IllegalStateException.class).verify();
        verifyNoInteractions(metrics);
    }

    @Test
    void shouldPropagateConnectivityFailureWhenPathBelongsToNoKnownService() {

        when(resolver.resolve("/api/cards/1")).thenReturn(Optional.empty());
        when(chain.filter(exchange)).thenReturn(Mono.error(new ConnectException("refused")));

        StepVerifier.create(filter.filter(exchange, chain)).expectError(ConnectException.class).verify();
        verifyNoInteractions(metrics);
    }

    @Test
    void shouldPropagateFailureWhenResponseIsAlreadyCommitted() {

        exchange.getResponse().setComplete().block();
        when(resolver.resolve("/api/cards/1")).thenReturn(Optional.of("cardManagement"));
        when(chain.filter(exchange)).thenReturn(Mono.error(new ConnectException("refused")));

        StepVerifier.create(filter.filter(exchange, chain)).expectError(ConnectException.class).verify();
        verifyNoInteractions(metrics);
    }

    @Test
    void shouldLeaveSuccessfulCallsUntouched() {

        when(chain.filter(exchange)).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verifyNoInteractions(resolver, metrics);
    }
}
