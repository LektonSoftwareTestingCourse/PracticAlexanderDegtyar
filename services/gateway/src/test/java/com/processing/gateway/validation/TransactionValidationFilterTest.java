package com.processing.gateway.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.gateway.metrics.GatewayMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionValidationFilterTest {

    private static final String VALID_BODY = """
            {"mti":"0100","stan":"000001","pan":"4000001234567890","processingCode":"000000",
             "amount":1500,"currencyCode":"643","transmissionDateTime":"2026-06-15T12:00:00Z",
             "terminalId":"TERM0001","terminalType":"POS","merchantId":"MERCH0000000002",
             "mcc":"5045","acquirerId":"ACQ002","issuerId":"ISS001"}""";

    @Mock
    private TransactionRequestValidator validator;

    @Mock
    private GatewayMetrics metrics;

    @Mock
    private GatewayFilterChain chain;

    private TransactionValidationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new TransactionValidationFilter(new ObjectMapper().findAndRegisterModules(), validator, metrics);
    }

    @Test
    void shouldForwardValidTransactionWithItsBodyIntact() {

        MockServerWebExchange exchange = post("/api/transactions", VALID_BODY);
        when(chain.filter(any())).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        ArgumentCaptor<ServerWebExchange> forwarded = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(chain).filter(forwarded.capture());
        String body = DataBufferUtils.join(forwarded.getValue().getRequest().getBody())
                .map(buffer -> buffer.toString(StandardCharsets.UTF_8)).block();
        assertThat(body).isEqualTo(VALID_BODY);
        assertThat(forwarded.getValue().getRequest().getHeaders().getContentLength())
                .isEqualTo(VALID_BODY.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void shouldAnswerBadRequestWithValidatorMessageWhenRuleIsViolated() {

        MockServerWebExchange exchange = post("/api/transactions", VALID_BODY);
        doThrow(new TransactionValidationException("Field 'amount' must be > 0"))
                .when(validator).validate(any(AuthorizationRequest.class));

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("\"error\":\"VALIDATION_ERROR\"")
                .contains("Field 'amount' must be > 0")
                .contains("\"serviceName\":\"gateway\"");
        verify(chain, never()).filter(any());
        verify(metrics).recordValidationRejected("invalid_request");
    }

    @Test
    void shouldAnswerBadRequestWhenBodyIsNotJson() {

        MockServerWebExchange exchange = post("/api/transactions", "this is not json");

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("Request body must be valid JSON");
        verify(metrics).recordValidationRejected("invalid_json");
        verifyNoInteractions(validator);
        verify(chain, never()).filter(any());
    }

    @Test
    void shouldAnswerBadRequestWhenBodyIsEmpty() {

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/transactions").build());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(metrics).recordValidationRejected("invalid_json");
    }

    @Test
    void shouldNotValidateOtherPathsOrMethods() {

        MockServerWebExchange search = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/transactions").build());
        MockServerWebExchange postToSubPath = post("/api/transactions/search", "ignored");
        when(chain.filter(any())).thenReturn(Mono.empty());

        StepVerifier.create(filter.filter(search, chain)).verifyComplete();
        StepVerifier.create(filter.filter(postToSubPath, chain)).verifyComplete();

        verifyNoInteractions(validator, metrics);
        verify(chain).filter(search);
        verify(chain).filter(postToSubPath);
    }

    private MockServerWebExchange post(String path, String body) {
        return MockServerWebExchange.from(MockServerHttpRequest.post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body));
    }
}
