package com.processing.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static com.processing.api.ApiSupport.*;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;

class SimulatorTest {
    @ParameterizedTest
    @CsvSource({"terminal,normal", "merchant,grocery"})
    void simulatorReturnsOneAuthorizationResult(String simulator, String scenario) {
        createCard(100000, 100000, 100000);

        given().spec(GATEWAY).body(Map.of("count", 1, "scenario", scenario, "tps", 10))
                .when().post("/api/simulator/" + simulator + "/run")
                .then().statusCode(200).body("totalSubmitted", equalTo(1), "transactions", hasSize(1),
                        "transactions[0].status", oneOf("APPROVED", "DECLINED"));
    }

    @ParameterizedTest
    @CsvSource({"terminal,normal", "merchant,grocery"})
    void simulatorRejectsZeroCount(String simulator, String scenario) {
        given().spec(GATEWAY).body(Map.of("count", 0, "scenario", scenario, "tps", 10))
                .when().post("/api/simulator/" + simulator + "/run")
                .then().statusCode(400).body("message", containsString("count"));
    }

    @Test
    void terminalStartsAndStopsContinuousMode() {
        createCard(100000, 100000, 100000);
        try {
            given().spec(GATEWAY).body(Map.of("tps", 1, "transactionType", "NORMAL"))
                    .when().post("/api/simulator/terminal/start-continuous")
                    .then().statusCode(200).body(isEmptyString());
        } finally {
            given().spec(GATEWAY)
                    .when().post("/api/simulator/terminal/stop")
                    .then().statusCode(200).body(isEmptyString());
        }
    }

    @Test
    void legacyOpenApiTerminalRoutesAreNotImplemented() {
        var terminal = "/api/simulator/terminal";
        given().spec(GATEWAY).body(Map.of("tps", 1, "transactionType", "NORMAL"))
                .when().post(terminal + "/start")
                .then().statusCode(500).body("serviceName", equalTo("terminal-simulator"),
                        "error", equalTo("Unexpected internal server error"),
                        "message", containsString("start"));

        given().spec(GATEWAY)
                .when().get(terminal + "/status")
                .then().statusCode(500).body("serviceName", equalTo("terminal-simulator"),
                        "error", equalTo("Unexpected internal server error"),
                        "message", containsString("status"));
    }

    @Test
    void merchantDirectoryExistsDirectlyButGatewayDoesNotRouteIt() {
        given().spec(service("merchant", 8084))
                .when().get("/api/simulator/merchants")
                .then().statusCode(200).body("", not(empty()), "[0].mcc", notNullValue());

        given().spec(GATEWAY)
                .when().get("/api/simulator/merchants")
                .then().statusCode(404).body("path", equalTo("/api/simulator/merchants"));
    }

    @Test
    void merchantFeeMatchesFeeLoggedBySwitch() {
        createCard(100000, 100000, 100000);
        Instant submittedAfter = Instant.now();

        String stan = given().spec(GATEWAY).body(Map.of("count", 1, "scenario", "grocery", "tps", 10))
                .when().post("/api/simulator/merchant/run")
                .then().statusCode(200).body("transactions", hasSize(1),
                        "transactions[0].stan", matchesPattern("[0-9]{6}"))
                .extract().path("transactions[0].stan");

        Map<String, Object> entry = await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(250))
                .until(() -> {
                    Map<String, Object> latest = given().spec(GATEWAY).queryParam("limit", 20)
                            .when().get("/api/dashboard/recent")
                            .then().statusCode(200).extract()
                            .path("find { it.stan == '" + stan + "' && it.acquiringFee != null }");
                    return latest != null && Instant.parse(latest.get("createdAt").toString()).isAfter(submittedAfter)
                            ? latest : null;
                }, notNullValue());

        given().spec(GATEWAY).body(Map.of("pan", entry.get("pan"), "stan", stan,
                        "amount", entry.get("amount"), "terminalId", entry.get("terminalId"),
                        "transmissionDateTime", entry.get("transmissionDateTime")))
                .when().post("/api/simulator/merchant/fee")
                .then().statusCode(200).body("acquirerFee", equalTo(entry.get("acquiringFee")));
    }

    @Test
    void merchantFeeRequiresTransmissionTime() {
        var request = transaction(unknownPan("400000"), 100);
        request.remove("transmissionDateTime");

        given().spec(GATEWAY).body(request)
                .when().post("/api/simulator/merchant/fee")
                .then().statusCode(400).body("error", equalTo("Invalid request"),
                        "message", containsString("transmissionDateTime"));
    }

    @Test
    void unknownMerchantFeeReturnsNotFound() {
        var request = transaction(unknownPan("400000"), 100);

        given().spec(GATEWAY).body(Map.of("pan", request.get("pan"), "stan", request.get("stan"),
                        "amount", request.get("amount"), "terminalId", request.get("terminalId"),
                        "transmissionDateTime", request.get("transmissionDateTime")))
                .when().post("/api/simulator/merchant/fee")
                .then().statusCode(404).body("error", not(blankOrNullString()), "message", not(blankOrNullString()));
    }

    @ParameterizedTest
    @CsvSource({"999999,false,404", "400000,true,500"})
    void missingOrFailedBinLookupHasEmptyBody(String bin, boolean fail, int status) {
        given().spec(service("bin", 8096)).queryParam("fail", fail)
                .when().get("/api/bin/{bin}", bin)
                .then().statusCode(status).body(isEmptyString());
    }

    @Test
    void binLookupResolvesKnownIssuer() {
        given().spec(service("bin", 8096))
                .when().get("/api/bin/{bin}", "400000")
                .then().statusCode(200).body("bin", equalTo("400000"), "issuerId", equalTo("ISS001"));
    }
}
