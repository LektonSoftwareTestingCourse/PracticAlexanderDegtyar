package com.processing.cardmanagement.models;

import com.processing.cardmanagement.events.CardServicePatchEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CardOutboxEventDataTest {

    private static final CardServicePatchEvent EVENT = new CardServicePatchEvent("4000001234567890");

    @Test
    void shouldStartPendingWithoutAttemptsAndErrors() {

        CardOutboxEventData data = new CardOutboxEventData(EVENT);

        assertThat(data.status()).isEqualTo(OutboxEventDataStatus.PENDING);
        assertThat(data.retryCount()).isZero();
        assertThat(data.processedAt()).isNull();
        assertThat(data.lastError()).isNull();
        assertThat(data.event()).isEqualTo(EVENT);
        assertThat(data.id()).isNotNull();
    }

    @Test
    void shouldBecomeProcessedWithTimestampKeepingAttemptCount() {

        CardOutboxEventData data = new CardOutboxEventData(EVENT).withRetry("timeout");

        CardOutboxEventData processed = data.processed();

        assertThat(processed.status()).isEqualTo(OutboxEventDataStatus.PROCESSED);
        assertThat(processed.processedAt()).isNotNull();
        assertThat(processed.retryCount()).isEqualTo(1);
        assertThat(processed.id()).isEqualTo(data.id());
    }

    @Test
    void shouldCountAttemptsAndRememberLastErrorWithoutChangingStatus() {

        CardOutboxEventData data = new CardOutboxEventData(EVENT);

        CardOutboxEventData retried = data.withRetry("first").withRetry("second");

        assertThat(retried.retryCount()).isEqualTo(2);
        assertThat(retried.lastError()).isEqualTo("second");
        assertThat(retried.status()).isEqualTo(OutboxEventDataStatus.PENDING);
        assertThat(data.retryCount()).isZero();
    }

    @Test
    void shouldBecomeFailedKeepingErrorAndAttempts() {

        CardOutboxEventData data = new CardOutboxEventData(EVENT).withRetry("broker down");

        CardOutboxEventData failed = data.failed();

        assertThat(failed.status()).isEqualTo(OutboxEventDataStatus.FAILED);
        assertThat(failed.lastError()).isEqualTo("broker down");
        assertThat(failed.retryCount()).isEqualTo(1);
        assertThat(failed.processedAt()).isNull();
    }
}
