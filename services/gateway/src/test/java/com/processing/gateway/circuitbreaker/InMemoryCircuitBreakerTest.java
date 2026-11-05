package com.processing.gateway.circuitbreaker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryCircuitBreakerTest {

    private static final String SERVICE = "switch";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-06-15T12:00:00Z"));

    private InMemoryCircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        breaker = InMemoryCircuitBreaker.forTesting(Duration.ofSeconds(10), 3, clock);
    }

    @Test
    void shouldAllowRequestsWhileClosed() {

        assertThat(breaker.allowRequest(SERVICE)).isTrue();
    }

    @Test
    void shouldStayClosedBelowFailureThreshold() {

        failTimes(2);

        assertThat(breaker.allowRequest(SERVICE)).isTrue();
    }

    @Test
    void shouldOpenAndRejectRequestsWhenFailureThresholdIsReached() {

        failTimes(3);

        assertThat(breaker.allowRequest(SERVICE)).isFalse();
    }

    @Test
    void shouldResetFailureCountAfterASuccess() {

        failTimes(2);

        breaker.recordSuccess(SERVICE);
        failTimes(2);

        assertThat(breaker.allowRequest(SERVICE)).isTrue();
    }

    @Test
    void shouldKeepRejectingUntilOpenDurationHasPassed() {

        failTimes(3);

        clock.advance(Duration.ofSeconds(9));

        assertThat(breaker.allowRequest(SERVICE)).isFalse();
    }

    @Test
    void shouldLetExactlyOneTrialRequestThroughAfterOpenDuration() {

        failTimes(3);
        clock.advance(Duration.ofSeconds(10));

        boolean trial = breaker.allowRequest(SERVICE);
        boolean concurrent = breaker.allowRequest(SERVICE);

        assertThat(trial).isTrue();
        assertThat(concurrent).isFalse();
    }

    @Test
    void shouldCloseAgainWhenTrialRequestSucceeds() {

        failTimes(3);
        clock.advance(Duration.ofSeconds(10));
        breaker.allowRequest(SERVICE);

        breaker.recordSuccess(SERVICE);

        assertThat(breaker.allowRequest(SERVICE)).isTrue();
        assertThat(breaker.allowRequest(SERVICE)).isTrue();
    }

    @Test
    void shouldReopenForAFullPeriodWhenTrialRequestFails() {

        failTimes(3);
        clock.advance(Duration.ofSeconds(10));
        breaker.allowRequest(SERVICE);

        breaker.recordFailure(SERVICE);
        clock.advance(Duration.ofSeconds(9));

        assertThat(breaker.allowRequest(SERVICE)).isFalse();
    }

    @Test
    void shouldAllowAnotherTrialWhenThePreviousOneWasReleasedWithoutResult() {

        failTimes(3);
        clock.advance(Duration.ofSeconds(10));
        breaker.allowRequest(SERVICE);

        breaker.releaseRequest(SERVICE);

        assertThat(breaker.allowRequest(SERVICE)).isTrue();
    }

    @Test
    void shouldTrackEachServiceIndependently() {

        failTimes(3);

        assertThat(breaker.allowRequest(SERVICE)).isFalse();
        assertThat(breaker.allowRequest("cardManagement")).isTrue();
    }

    private void failTimes(int times) {
        IntStream.range(0, times).forEach(i -> breaker.recordFailure(SERVICE));
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
