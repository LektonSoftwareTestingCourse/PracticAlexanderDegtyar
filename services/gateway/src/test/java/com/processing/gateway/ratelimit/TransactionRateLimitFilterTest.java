package com.processing.gateway.ratelimit;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionRateLimitFilterTest {

    private static final String LIMIT_KEY = "POST /api/transactions:203.0.113.7";

    @Mock
    private InMemoryRateLimiter rateLimiter;

    @Mock
    private ClientIpResolver clientIpResolver;

    @Mock
    private GatewayMetrics metrics;

    @Mock
    private GatewayFilterChain chain;

    private TransactionRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        filter = new TransactionRateLimitFilter(rateLimiter, clientIpResolver, new ObjectMapper(), metrics);
    }

    @Test
    void shouldPassRequestThroughWhenLimiterAllowsIt() {

        MockServerWebExchange exchange = exchange(MockServerHttpRequest.post("/api/transactions"));
        when(clientIpResolver.resolve(exchange)).thenReturn("203.0.113.7");
        when(rateLimiter.allowRequest(LIMIT_KEY)).thenReturn(true);
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(exchange);
        verifyNoInteractions(metrics);
    }

    @Test
    void shouldAnswerTooManyRequestsWithRetryAfterWhenLimitIsExceeded() {

        MockServerWebExchange exchange = exchange(MockServerHttpRequest.post("/api/transactions"));
        when(clientIpResolver.resolve(exchange)).thenReturn("203.0.113.7");
        when(rateLimiter.allowRequest(LIMIT_KEY)).thenReturn(false);

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("RATE_LIMIT_EXCEEDED")
                .contains("\"retryAfterMs\":1000");
        verify(chain, never()).filter(any());
        verify(metrics).recordRateLimitRejected();
    }

    @Test
    void shouldNotLimitRequestsToOtherPathsOrMethods() {

        MockServerWebExchange read = exchange(MockServerHttpRequest.get("/api/transactions"));
        MockServerWebExchange search = exchange(MockServerHttpRequest.post("/api/transactions/search"));
        when(chain.filter(any())).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(read, chain)).verifyComplete();
        StepVerifier.create(filter.filter(search, chain)).verifyComplete();

        verifyNoInteractions(rateLimiter);
        verify(chain).filter(read);
        verify(chain).filter(search);
    }

    private MockServerWebExchange exchange(MockServerHttpRequest.BodyBuilder request) {
        return MockServerWebExchange.from(request.build());
    }

    private MockServerWebExchange exchange(MockServerHttpRequest.BaseBuilder<?> request) {
        return MockServerWebExchange.from(request.build());
    }
}
