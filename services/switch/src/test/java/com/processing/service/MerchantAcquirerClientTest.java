package com.processing.service;

import com.processing.config.SwitchProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MerchantAcquirerClientTest {

    private static final String FEE_URL = "http://merchant:8084/api/simulator/merchant/fee";

    private MockRestServiceServer server;
    private MerchantAcquirerClient client;

    @BeforeEach
    void setUp() {
        SwitchProperties properties = new SwitchProperties(
                "1.0.0", null, null, null, "http://merchant:8084", null, null, null);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new MerchantAcquirerClient(properties, builder.build());
    }

    @Test
    void shouldReturnFeeFromMerchantAcquirerAndSendTransactionDetails() {

        server.expect(requestTo(FEE_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"stan":"000001","pan":"4000001234567890","terminalId":"TERM0001","amount":1500}"""))
                .andRespond(withSuccess("{\"acquirerFee\":12.50}", MediaType.APPLICATION_JSON));

        BigDecimal fee = client.fetchAcquiringFee(
                Instant.parse("2026-06-15T12:00:00Z"), "000001", "4000001234567890", "TERM0001",
                new BigDecimal("1500"));

        assertThat(fee).isEqualByComparingTo("12.50");
        server.verify();
    }

    @Test
    void shouldReturnNullWhenMerchantAcquirerFails() {

        server.expect(requestTo(FEE_URL)).andRespond(withServerError());

        BigDecimal fee = client.fetchAcquiringFee(
                Instant.now(), "000001", "4000001234567890", "TERM0001", BigDecimal.TEN);

        assertThat(fee).isNull();
    }

    @Test
    void shouldReturnNullWhenResponseHasNoBody() {

        server.expect(requestTo(FEE_URL)).andRespond(withSuccess());

        BigDecimal fee = client.fetchAcquiringFee(
                Instant.now(), "000001", "4000001234567890", "TERM0001", BigDecimal.TEN);

        assertThat(fee).isNull();
    }
}
