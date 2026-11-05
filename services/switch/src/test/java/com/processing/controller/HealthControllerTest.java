package com.processing.controller;

import com.processing.config.SwitchProperties;
import com.processing.dto.HealthResponse;
import com.processing.service.AuthorizationClient;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HealthControllerTest {

    @Mock
    private AuthorizationClient authorizationClient;

    @ParameterizedTest(name = "Authorization: {0}")
    @ValueSource(strings = {"ok", "down"})
    void shouldReportOwnStatusAndAuthorizationDependency(String authorizationStatus) {

        SwitchProperties properties = new SwitchProperties(
                "1.2.3", null, null, null, null, null, null, null);
        when(authorizationClient.checkHealth()).thenReturn(authorizationStatus);
        HealthController controller = new HealthController(properties, authorizationClient);

        ResponseEntity<HealthResponse> entity = controller.health();

        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.OK);
        HealthResponse body = entity.getBody();
        assertThat(body.status()).isEqualTo("ok");
        assertThat(body.service()).isEqualTo("switch");
        assertThat(body.version()).isEqualTo("1.2.3");
        assertThat(body.dependencies()).containsEntry("authorization", authorizationStatus);
    }
}
