package com.processing.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerFactoryTest {

    @Test
    void shouldCreateClosedBreakerWithConfiguredThresholds() {

        CircuitBreaker breaker = CircuitBreakerFactory.authorizationCircuitBreaker(
                properties(new SwitchProperties.CircuitBreakerProperties(50, 10, 5, 30_000)));

        assertThat(breaker.getName()).isEqualTo("authorization");
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.getCircuitBreakerConfig().getFailureRateThreshold()).isEqualTo(50f);
        assertThat(breaker.getCircuitBreakerConfig().getSlidingWindowSize()).isEqualTo(10);
        assertThat(breaker.getCircuitBreakerConfig().getMinimumNumberOfCalls()).isEqualTo(5);
    }

    @Test
    void shouldOpenAndRejectCallsWhenFailureRateReachesThreshold() {

        CircuitBreaker breaker = CircuitBreakerFactory.authorizationCircuitBreaker(
                properties(new SwitchProperties.CircuitBreakerProperties(50, 2, 2, 60_000)));

        breaker.onError(0, TimeUnit.MILLISECONDS, new IllegalStateException("down"));
        breaker.onError(0, TimeUnit.MILLISECONDS, new IllegalStateException("down"));

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breaker.tryAcquirePermission()).isFalse();
    }

    @Test
    void shouldStayClosedWhileFewerCallsThanMinimumWereMade() {

        CircuitBreaker breaker = CircuitBreakerFactory.authorizationCircuitBreaker(
                properties(new SwitchProperties.CircuitBreakerProperties(50, 10, 5, 30_000)));

        List.of(1, 2, 3, 4).forEach(i ->
                breaker.onError(0, TimeUnit.MILLISECONDS, new IllegalStateException("down")));

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void shouldExposeBothResilienceBeansFromConfiguration() {

        SwitchProperties properties = new SwitchProperties(
                "1.0.0", null, null, null, null, null,
                new SwitchProperties.RetryProperties(3, List.of(1L)),
                new SwitchProperties.CircuitBreakerSection(
                        new SwitchProperties.CircuitBreakerProperties(50, 10, 5, 30_000)));
        Resilience4jConfig config = new Resilience4jConfig();

        assertThat(config.authorizationRetry(properties).getName()).isEqualTo("authorization");
        assertThat(config.authorizationCircuitBreaker(properties).getName()).isEqualTo("authorization");
    }

    private SwitchProperties properties(SwitchProperties.CircuitBreakerProperties authorization) {
        return new SwitchProperties(
                "1.0.0", null, null, null, null, null, null,
                new SwitchProperties.CircuitBreakerSection(authorization));
    }
}
