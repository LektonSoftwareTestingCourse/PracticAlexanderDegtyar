package com.processing.cardmanagement.services;

import com.processing.cardmanagement.exceptions.OutOfRetriesException;
import com.processing.cardmanagement.services.retries.RetryServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryServiceImplTest {

    private final RetryServiceImpl retryService = new RetryServiceImpl();

    @Test
    void shouldReturnResultOfTheFirstSuccessfulAttemptWithoutRepeating() {

        AtomicInteger calls = new AtomicInteger();

        String result = retryService.supply(3, () -> {
            calls.incrementAndGet();
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void shouldRepeatUntilSupplierSucceeds() {

        AtomicInteger calls = new AtomicInteger();

        String result = retryService.supply(3, () -> {
            if (calls.incrementAndGet() < 3) {
                throw new IllegalStateException("not yet");
            }
            return "third time lucky";
        });

        assertThat(result).isEqualTo("third time lucky");
        assertThat(calls.get()).isEqualTo(3);
    }

    @ParameterizedTest(name = "maxRetries={0} -> всего попыток {1}")
    @CsvSource({"0, 1", "1, 2", "3, 4"})
    void shouldMakeOneInitialAttemptPlusConfiguredRetriesBeforeGivingUp(int maxRetries, int expectedAttempts) {

        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> retryService.supply(maxRetries, () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("always");
        })).isInstanceOf(OutOfRetriesException.class);

        assertThat(calls.get()).isEqualTo(expectedAttempts);
    }

    @Test
    void shouldKeepTheLastFailureAsTheReasonOfExhaustion() {

        IllegalStateException failure = new IllegalStateException("db down");

        assertThatThrownBy(() -> retryService.supply(1, () -> {
            throw failure;
        }))
                .isInstanceOfSatisfying(OutOfRetriesException.class,
                        ex -> assertThat(ex.getReason()).isSameAs(failure))
                .hasMessageContaining("db down");
    }
}
