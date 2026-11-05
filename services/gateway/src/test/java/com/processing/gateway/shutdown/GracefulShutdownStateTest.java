package com.processing.gateway.shutdown;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class GracefulShutdownStateTest {

    @Test
    void shouldNotBeShuttingDownByDefault() {

        assertThat(new GracefulShutdownState().isShuttingDown()).isFalse();
    }

    @Test
    void shouldRememberThatShutdownHasStarted() {

        GracefulShutdownState state = new GracefulShutdownState();

        state.startShutdown();

        assertThat(state.isShuttingDown()).isTrue();
    }

    @ParameterizedTest(name = "drain {0} мс -> Retry-After {1} с")
    @CsvSource({"30000, 30", "1500, 1", "999, 1", "0, 1"})
    void shouldAskClientsToRetryAfterDrainPeriodButNotLessThanOneSecond(long drainMillis, long expectedSeconds) {

        ShutdownProperties properties = new ShutdownProperties();
        properties.setDrainPeriod(Duration.ofMillis(drainMillis));

        assertThat(properties.retryAfterSeconds()).isEqualTo(expectedSeconds);
    }
}
