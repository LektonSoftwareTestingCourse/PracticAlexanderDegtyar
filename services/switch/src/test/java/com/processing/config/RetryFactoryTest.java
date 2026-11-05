package com.processing.config;

import io.github.resilience4j.retry.Retry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryFactoryTest {

    @Test
    void shouldNameRetriesAfterTheirTarget() {

        SwitchProperties properties = properties(3, List.of(1L));

        assertThat(RetryFactory.authorizationRetry(properties).getName()).isEqualTo("authorization");
        assertThat(RetryFactory.loggerRetry(properties).getName()).isEqualTo("logger");
    }

    @Test
    void shouldAttemptTheConfiguredNumberOfTimesAndThenFail() {

        Retry retry = RetryFactory.authorizationRetry(properties(3, List.of(1L, 1L, 1L)));
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> Retry.decorateCallable(retry, () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("down");
        }).call()).isInstanceOf(IllegalStateException.class);

        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void shouldWaitAccordingToBackoffListBetweenAttempts() {

        Retry retry = RetryFactory.authorizationRetry(properties(3, List.of(5L, 7L, 9L)));
        List<Long> waits = collectWaits(retry);

        failAlways(retry);

        assertThat(waits).containsExactly(5L, 7L);
    }

    @Test
    void shouldReuseLastBackoffValueWhenListIsShorterThanAttempts() {

        Retry retry = RetryFactory.authorizationRetry(properties(4, List.of(2L, 6L)));
        List<Long> waits = collectWaits(retry);

        failAlways(retry);

        assertThat(waits).containsExactly(2L, 6L, 6L);
    }

    @Test
    void shouldUseMinimalPauseWhenBackoffListIsMissingOrEmpty() {

        Retry withoutList = RetryFactory.authorizationRetry(properties(3, null));
        Retry withEmptyList = RetryFactory.authorizationRetry(properties(3, List.of()));
        List<Long> firstWaits = collectWaits(withoutList);
        List<Long> secondWaits = collectWaits(withEmptyList);

        failAlways(withoutList);
        failAlways(withEmptyList);

        assertThat(firstWaits).containsExactly(1L, 1L);
        assertThat(secondWaits).containsExactly(1L, 1L);
    }

    @Test
    void shouldRaiseNonPositiveBackoffValuesToMinimalPause() {

        Retry retry = RetryFactory.authorizationRetry(properties(3, List.of(0L, -5L)));
        List<Long> waits = collectWaits(retry);

        failAlways(retry);

        assertThat(waits).containsExactly(1L, 1L);
    }

    private List<Long> collectWaits(Retry retry) {
        List<Long> waits = new ArrayList<>();
        retry.getEventPublisher().onRetry(event -> waits.add(event.getWaitInterval().toMillis()));
        return waits;
    }

    private void failAlways(Retry retry) {
        assertThatThrownBy(() -> Retry.decorateCallable(retry, () -> {
            throw new IllegalStateException("down");
        }).call()).isInstanceOf(IllegalStateException.class);
    }

    private SwitchProperties properties(int maxAttempts, List<Long> backoffMs) {
        return new SwitchProperties(
                "1.0.0", null, null, null, null, null,
                new SwitchProperties.RetryProperties(maxAttempts, backoffMs), null);
    }
}
