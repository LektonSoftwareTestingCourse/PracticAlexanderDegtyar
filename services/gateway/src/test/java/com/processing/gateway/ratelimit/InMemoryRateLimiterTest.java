package com.processing.gateway.ratelimit;

import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRateLimiterTest {

    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;

    private InMemoryRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = InMemoryRateLimiter.forTesting(3, 1.0, Duration.ofMinutes(10), 100, ticker);
    }

    @Test
    void shouldAllowRequestsUpToCapacityAndRejectTheNext() {

        long allowed = IntStream.range(0, 3).filter(i -> limiter.allowRequest("client")).count();
        boolean fourth = limiter.allowRequest("client");

        assertThat(allowed).isEqualTo(3);
        assertThat(fourth).isFalse();
    }

    @Test
    void shouldRefillOneTokenPerSecond() {

        IntStream.range(0, 3).forEach(i -> limiter.allowRequest("client"));

        advance(1, TimeUnit.SECONDS);
        boolean afterOneSecond = limiter.allowRequest("client");
        boolean immediatelyAfter = limiter.allowRequest("client");

        assertThat(afterOneSecond).isTrue();
        assertThat(immediatelyAfter).isFalse();
    }

    @Test
    void shouldNotAllowRequestBeforeAFullTokenIsRefilled() {

        IntStream.range(0, 3).forEach(i -> limiter.allowRequest("client"));

        advance(999, TimeUnit.MILLISECONDS);

        assertThat(limiter.allowRequest("client")).isFalse();
    }

    @Test
    void shouldNeverAccumulateMoreTokensThanCapacity() {

        IntStream.range(0, 3).forEach(i -> limiter.allowRequest("client"));

        advance(1, TimeUnit.HOURS);
        long allowed = IntStream.range(0, 10).filter(i -> limiter.allowRequest("client")).count();

        assertThat(allowed).isEqualTo(3);
    }

    @Test
    void shouldKeepSeparateBucketsForDifferentClients() {

        IntStream.range(0, 3).forEach(i -> limiter.allowRequest("noisy"));

        assertThat(limiter.allowRequest("noisy")).isFalse();
        assertThat(limiter.allowRequest("quiet")).isTrue();
    }

    @Test
    void shouldRejectEverythingWhenCapacityIsZero() {

        InMemoryRateLimiter closed = InMemoryRateLimiter.forTesting(0, 100.0, Duration.ofMinutes(1), 10, ticker);

        assertThat(closed.allowRequest("client")).isFalse();
    }

    @Test
    void shouldTreatNegativeConfigurationAsZero() {

        InMemoryRateLimiter negative = InMemoryRateLimiter.forTesting(-5, -1.0, Duration.ofMinutes(1), 10, ticker);

        advance(10, TimeUnit.SECONDS);

        assertThat(negative.allowRequest("client")).isFalse();
    }

    @Test
    void shouldStartAgainWithFullBucketAfterBucketExpired() {

        IntStream.range(0, 3).forEach(i -> limiter.allowRequest("client"));

        advance(11, TimeUnit.MINUTES);
        limiter.cleanUp();

        assertThat(limiter.allowRequest("client")).isTrue();
    }

    private void advance(long amount, TimeUnit unit) {
        nanos.addAndGet(unit.toNanos(amount));
    }
}
