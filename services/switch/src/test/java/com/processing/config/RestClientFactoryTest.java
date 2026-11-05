package com.processing.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

class RestClientFactoryTest {

    @Test
    void shouldBuildRestClientFromConfiguredTimeouts() {

        SwitchProperties properties = new SwitchProperties(
                "1.0.0", null, null, null, null,
                new SwitchProperties.HttpProperties(3000, 5000, 2000), null, null);

        RestClient client = new RestClientFactory().restClient(properties);

        assertThat(client).isNotNull();
    }
}
