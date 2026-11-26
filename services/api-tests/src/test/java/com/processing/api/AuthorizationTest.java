package com.processing.api;

import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.stream.Stream;

import static com.processing.api.ApiSupport.*;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;

class AuthorizationTest {
    @Test
    void gatewayApprovalBecomesSearchable() {
        String pan = createCard(1000, 500, 800);
        var request = transaction(pan, 100);
        request.remove("issuerId");

        String rrn = given().spec(GATEWAY).body(request)
                .when().post(TRANSACTIONS)
                .then().statusCode(200).contentType("application/json")
                .body("$", hasKey("declineReason"))
                .body("stan", equalTo(request.get("stan")), "responseCode", equalTo("00"),
                        "status", equalTo("APPROVED"), "rrn", matchesPattern("[0-9]{12}"),
                        "mti", equalTo("0100"),
                        "authCode", matchesPattern("[A-Z0-9]{6}"), "declineReason", nullValue(),
                        "processingTimeMs", greaterThanOrEqualTo(0))
                .extract().path("rrn");

        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(250)).untilAsserted(() ->
                given().spec(GATEWAY).queryParam("pan", pan)
                        .when().get(SEARCH)
                        .then().statusCode(200)
                        .body("transactions.findAll { it.rrn == '" + rrn + "' }.stan",
                                contains(request.get("stan")))
                        .body("transactions", hasSize(1), "transactions[0].status", equalTo("APPROVED")));
        given().spec(GATEWAY)
                .when().get(CARD, pan)
                .then().statusCode(200).body("availableBalance", equalTo(900));
    }

    @ParameterizedTest
    @CsvSource({
        "BLOCKED,100,05,CARD_BLOCKED,403",
        "INACTIVE,100,05,CARD_INACTIVE,403",
        "EXPIRED,100,54,CARD_EXPIRED,403",
        "FUTURE,100,54,CARD_EXPIRED,403",
        "ACTIVE,1001,51,INSUFFICIENT_FUNDS,422",
        "ACTIVE,501,61,EXCEEDS_AMOUNT_LIMIT,400",
        "MISSING,100,14,CARD_NOT_FOUND,404"
    })
    void authorizationBusinessDeclines(String state, int amount, String code, String reason, int http) {
        String pan = "MISSING".equals(state) ? unknownPan("400000") : createCard(1000, 500, 800);
        if (!state.equals("ACTIVE") && !state.equals("FUTURE") && !state.equals("MISSING")) {
            given().spec(GATEWAY).body(Map.of("status", state))
                    .when().patch(CARD, pan)
                    .then().statusCode(200).body("status", equalTo(state));
        }
        var request = transaction(pan, amount);
        if (state.equals("FUTURE")) {
            request.put("transmissionDateTime", "2099-01-01T00:00:00Z");
        }

        assertDeclined(AUTH, AUTHORIZE, request, http, code, reason);
        assertDeclined(GATEWAY, TRANSACTIONS, request, 200, code, reason);
    }

    @Test
    void switchReturnsUnknownBinDecline() {
        var request = transaction(unknownPan("999999"), 100);

        given().spec(SWITCH).body(request)
                .when().post("/api/internal/route")
                .then().statusCode(200)
                .body("stan", equalTo(request.get("stan")), "status", equalTo("DECLINED"),
                        "responseCode", equalTo("14"), "declineReason", equalTo("CARD_NOT_FOUND"));
    }

    @Test
    void rollbackRestoresFundsAndDirectAuthorizationNeverAppearsInLogger() {
        String pan = createCard(1000, 500, 800);
        var request = transaction(pan, 100);
        String rrn = given().spec(AUTH).body(request)
                .when().post(AUTHORIZE)
                .then().statusCode(200).body("responseCode", equalTo("00"))
                .extract().path("rrn");
        var reversal = Map.of("rrn", rrn, "pan", pan, "amount", 100);

        given().spec(AUTH).body(reversal)
                .when().post("/api/internal/rollback")
                .then().statusCode(200).body("responseCode", equalTo("00"), "rrn", equalTo(rrn));

        given().spec(AUTH).body(reversal)
                .when().post("/api/internal/rollback")
                .then().statusCode(409)
                .body("responseCode", equalTo("05"), "declineReason", equalTo("ALREADY_ROLLED_BACK"));

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
                    given().spec(GATEWAY).queryParam("pan", pan)
                            .when().get(SEARCH)
                            .then().statusCode(200).body("transactions", empty());
                    given().spec(GATEWAY)
                            .when().get(CARD, pan)
                            .then().statusCode(200).body("availableBalance", equalTo(1000));
                });
    }

    @Test
    void rollbackRejectsUnknownReservation() {
        String pan = createCard(1000, 500, 800);

        given().spec(AUTH).body(Map.of("pan", pan, "rrn", digits(12), "amount", 100))
                .when().post("/api/internal/rollback")
                .then().statusCode(404).body("status", equalTo("DECLINED"), "responseCode", equalTo("14"),
                        "declineReason", equalTo("TRANSACTION_NOT_FOUND"));
    }

    @Test
    void monthlyLimitStillAppliesAcrossUtcDays() {
        String pan = createCard(1000, 500, 600);
        LocalDate month = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
        var prior = transaction(pan, 400);
        prior.put("transmissionDateTime", month.atStartOfDay(ZoneOffset.UTC).toInstant().toString());
        given().spec(AUTH).body(prior)
                .when().post(AUTHORIZE)
                .then().statusCode(200).body("responseCode", equalTo("00"));

        var current = transaction(pan, 201);
        current.put("transmissionDateTime", month.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toString());
        assertDeclined(AUTH, AUTHORIZE, current, 400, "61", "EXCEEDS_AMOUNT_LIMIT");
    }

    @ParameterizedTest
    @CsvSource(value = {"{ | Request body must be valid JSON", "null | Request body is required"}, delimiter = '|')
    void gatewayRejectsInvalidJsonBody(String body, String rule) {
        given().spec(GATEWAY).body(body)
                .when().post(TRANSACTIONS)
                .then().statusCode(400).body("serviceName", equalTo("gateway"),
                        "error", equalTo("VALIDATION_ERROR"), "message", equalTo(rule));
    }

    @ParameterizedTest
    @MethodSource("missingGatewayFields")
    void gatewayRequiresField(String field) {
        var request = transaction(unknownPan("400000"), 100);
        request.remove(field);

        given().spec(GATEWAY).body(request)
                .when().post(TRANSACTIONS)
                .then().statusCode(400).body("error", equalTo("VALIDATION_ERROR"),
                        "serviceName", equalTo("gateway"), "message", equalTo("Field '" + field + "' is required"));
    }

    static Stream<String> missingGatewayFields() {
        return Stream.of("mti", "stan", "pan", "processingCode", "amount", "currencyCode",
                "transmissionDateTime", "terminalId", "merchantId", "mcc", "acquirerId");
    }

    @ParameterizedTest
    @CsvSource({
        "mti,0200,must be '0100'", "pan,400000123456789,must be exactly 16 digits",
        "pan,40000012345678901,must be exactly 16 digits", "pan,400000123456789x,must be exactly 16 digits",
        "currencyCode,64,must be exactly 3 characters", "currencyCode,6430,must be exactly 3 characters",
        "mcc,541,must be exactly 4 digits", "mcc,54110,must be exactly 4 digits",
        "mcc,541x,must be exactly 4 digits", "amount,0,must be > 0", "amount,-1,must be > 0"
    })
    void gatewayRejectsInvalidField(String field, String value, String rule) {
        var request = transaction(unknownPan("400000"), 100);
        request.put(field, field.equals("amount") ? Integer.parseInt(value) : value);

        given().spec(GATEWAY).body(request)
                .when().post(TRANSACTIONS)
                .then().statusCode(400).body("serviceName", equalTo("gateway"),
                        "error", equalTo("VALIDATION_ERROR"),
                        "message", equalTo("Field '" + field + "' " + rule));
    }

    private void assertDeclined(RequestSpecification service, String path, Map<String, Object> request,
                                int http, String code, String reason) {
        given().spec(service).body(request)
                .when().post(path)
                .then().statusCode(http)
                .body("status", equalTo("DECLINED"), "responseCode", equalTo(code),
                        "declineReason", equalTo(reason), "stan", equalTo(request.get("stan")),
                        "rrn", nullValue(), "authCode", nullValue());
    }
}
