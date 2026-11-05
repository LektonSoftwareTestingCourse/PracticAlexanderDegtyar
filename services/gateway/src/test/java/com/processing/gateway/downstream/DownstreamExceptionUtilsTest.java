package com.processing.gateway.downstream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.client.ResourceAccessException;
import reactor.netty.http.client.PrematureCloseException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DownstreamExceptionUtilsTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("unavailabilityCauses")
    void shouldRecognizeConnectivityFailuresAsUnavailable(Throwable failure) {

        assertThat(DownstreamExceptionUtils.isDownstreamUnavailable(failure)).isTrue();
    }

    static Stream<Throwable> unavailabilityCauses() {
        return Stream.of(
                new ConnectException("refused"),
                new SocketTimeoutException("read timed out"),
                new HttpTimeoutException("timeout"),
                new HttpConnectTimeoutException("connect timeout"),
                new ResourceAccessException("I/O error"),
                PrematureCloseException.TEST_EXCEPTION
        );
    }

    @Test
    void shouldFindUnavailabilityCauseNestedDeepInsideTheChain() {

        Throwable failure = new IllegalStateException("outer",
                new RuntimeException("middle", new ConnectException("refused")));

        assertThat(DownstreamExceptionUtils.isDownstreamUnavailable(failure)).isTrue();
    }

    @Test
    void shouldNotTreatBusinessOrProgrammingErrorsAsUnavailability() {

        assertThat(DownstreamExceptionUtils.isDownstreamUnavailable(new IllegalStateException("bug"))).isFalse();
        assertThat(DownstreamExceptionUtils.isDownstreamUnavailable(new IOException("other io"))).isFalse();
        assertThat(DownstreamExceptionUtils.isDownstreamUnavailable(null)).isFalse();
    }

    @Test
    void shouldRethrowIoExceptionAsIs() {

        IOException failure = new IOException("io");

        assertThatThrownBy(() -> DownstreamExceptionUtils.rethrow(failure)).isSameAs(failure);
    }

    @Test
    void shouldRethrowRuntimeExceptionAsIs() {

        IllegalArgumentException failure = new IllegalArgumentException("bad");

        assertThatThrownBy(() -> DownstreamExceptionUtils.rethrow(failure)).isSameAs(failure);
    }

    @Test
    void shouldWrapCheckedNonIoExceptionIntoIoException() {

        Exception failure = new Exception("checked");

        assertThatThrownBy(() -> DownstreamExceptionUtils.rethrow(failure))
                .isInstanceOf(IOException.class)
                .hasCause(failure);
    }
}
