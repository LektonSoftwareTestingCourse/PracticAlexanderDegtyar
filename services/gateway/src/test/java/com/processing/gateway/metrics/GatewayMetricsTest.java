package com.processing.gateway.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayMetricsTest {

    private SimpleMeterRegistry registry;
    private GatewayMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new GatewayMetrics(registry);
    }

    @Test
    void shouldCountRateLimitRejections() {

        metrics.recordRateLimitRejected();
        metrics.recordRateLimitRejected();

        assertThat(rejected("rate_limit", "gateway")).isEqualTo(2.0);
    }

    @Test
    void shouldCountValidationRejectionsByReason() {

        metrics.recordValidationRejected("invalid_json");
        metrics.recordValidationRejected("invalid_request");
        metrics.recordValidationRejected("invalid_request");

        assertThat(rejected("validation_invalid_json", "gateway")).isEqualTo(1.0);
        assertThat(rejected("validation_invalid_request", "gateway")).isEqualTo(2.0);
    }

    @Test
    void shouldCountOpenCircuitRejectionsPerService() {

        metrics.recordCircuitOpen("switch");

        assertThat(rejected("circuit_open", "switch")).isEqualTo(1.0);
        assertThat(rejected("circuit_open", "logger")).isZero();
    }

    @Test
    void shouldCountShutdownRejections() {

        metrics.recordGracefulShutdownRejected();

        assertThat(rejected("shutting_down", "gateway")).isEqualTo(1.0);
    }

    @Test
    void shouldCountDownstreamUnavailabilityPerService() {

        metrics.recordDownstreamUnavailable("switch");
        metrics.recordDownstreamUnavailable("switch");

        assertThat(registry.counter("gateway.downstream.unavailable", "service", "switch").count()).isEqualTo(2.0);
    }

    @Test
    void shouldCountCardsCacheHitsMissesAndInvalidations() {

        metrics.recordCardsCacheHit();
        metrics.recordCardsCacheMiss();
        metrics.recordCardsCacheMiss();
        metrics.recordCardsCacheInvalidation();

        assertThat(registry.counter("gateway.cache.requests", "cache", "cards", "result", "hit").count())
                .isEqualTo(1.0);
        assertThat(registry.counter("gateway.cache.requests", "cache", "cards", "result", "miss").count())
                .isEqualTo(2.0);
        assertThat(registry.counter("gateway.cache.invalidations", "cache", "cards").count()).isEqualTo(1.0);
    }

    private double rejected(String reason, String service) {
        return registry.counter("gateway.requests.rejected", "reason", reason, "service", service).count();
    }
}
