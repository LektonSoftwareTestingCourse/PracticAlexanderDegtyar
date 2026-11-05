package com.processing.gateway.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {

    private final ClientIpResolver resolver = new ClientIpResolver();

    @Test
    void shouldUseFirstAddressOfXForwardedForAndTrimIt() {

        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/x")
                .header("X-Forwarded-For", " 203.0.113.7 , 10.0.0.1, 10.0.0.2"));

        assertThat(resolver.resolve(exchange)).isEqualTo("203.0.113.7");
    }

    @Test
    void shouldPreferXForwardedForOverXRealIp() {

        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/x")
                .header("X-Forwarded-For", "203.0.113.7")
                .header("X-Real-IP", "198.51.100.1"));

        assertThat(resolver.resolve(exchange)).isEqualTo("203.0.113.7");
    }

    @Test
    void shouldFallBackToXRealIpWhenForwardedForIsBlank() {

        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/x")
                .header("X-Forwarded-For", "   ")
                .header("X-Real-IP", " 198.51.100.1 "));

        assertThat(resolver.resolve(exchange)).isEqualTo("198.51.100.1");
    }

    @Test
    void shouldFallBackToRemoteAddressWhenNoProxyHeadersArePresent() throws UnknownHostException {

        InetSocketAddress remote = new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 5}), 5555);
        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/x").remoteAddress(remote));

        assertThat(resolver.resolve(exchange)).isEqualTo("127.0.0.5");
    }

    @Test
    void shouldReportUnknownWhenNothingIsAvailable() {

        MockServerWebExchange exchange = exchange(MockServerHttpRequest.get("/x"));

        assertThat(resolver.resolve(exchange)).isEqualTo("unknown");
    }

    private MockServerWebExchange exchange(MockServerHttpRequest.BaseBuilder<?> request) {
        return MockServerWebExchange.from(request.build());
    }
}
