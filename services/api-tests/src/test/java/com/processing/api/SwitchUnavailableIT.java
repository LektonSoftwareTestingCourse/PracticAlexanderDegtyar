package com.processing.api;

import org.junit.jupiter.api.Test;

import static com.processing.api.ApiSupport.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

class SwitchUnavailableIT {
    @Test
    void gatewayReportsUnavailableSwitchInHealthAndTransactionResponse() {
        var request = transaction(unknownPan("400000"), 100);

        given().spec(GATEWAY)
                .when().get("/health")
                .then().statusCode(503)
                .body("status", equalTo("degraded"), "services.switch", equalTo("unavailable"));

        given().spec(GATEWAY).body(request)
                .when().post(TRANSACTIONS)
                .then().statusCode(503).body("message", containsStringIgnoringCase("switch"),
                        "serviceName", equalTo("switch"));
    }
}
