package com.processing.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.processing.api.ApiSupport.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

class CardsTest {
    @Test
    void cardLifecycleCanBeObservedThroughGateway() {
        String pan = createCard(1000, 500, 800);

        given().spec(GATEWAY).body(Map.of("status", "BLOCKED", "dailyLimit", 300))
                .when().patch(CARD, pan)
                .then().statusCode(200).body("pan", equalTo(pan), "status", equalTo("BLOCKED"),
                        "dailyLimit", equalTo(300), "availableBalance", equalTo(1000));

        given().spec(GATEWAY).body(Map.of("pans", List.of(pan), "status", "ACTIVE"))
                .when().post("/api/cards/bulk-update")
                .then().statusCode(200).body("updated", equalTo(1));

        given().spec(GATEWAY)
                .when().get(CARD, pan)
                .then().statusCode(200).body("status", equalTo("ACTIVE"), "dailyLimit", equalTo(300));

        given().spec(GATEWAY)
                .when().delete(CARD, pan)
                .then().statusCode(204).body(isEmptyString());
    }

    @Test
    void generatesOneCardAndListsUsingBinFilter() {
        given().spec(GATEWAY).body(Map.of("count", 1, "bins", List.of("400000")))
                .when().post("/api/cards/generate")
                .then().statusCode(201).body("generated", equalTo(1), "cards", hasSize(1),
                        "cards[0].pan", matchesPattern("400000[0-9]{10}"));

        given().spec(GATEWAY).queryParam("bin", "400000").queryParam("limit", 1).queryParam("offset", 0)
                .queryParam("status", "ACTIVE").queryParam("issuerId", "ISS001")
                .queryParam("startDate", "2000-01-01T00:00:00Z").queryParam("endDate", "2099-01-01T00:00:00Z")
                .when().get("/api/cards")
                .then().statusCode(200).body("cards", hasSize(1), "cards[0].bin", equalTo("400000"),
                        "total", greaterThanOrEqualTo(1));
    }

    @Test
    void unknownCardReturnsCardManagementError() {
        String pan = unknownPan("400000");

        given().spec(GATEWAY)
                .when().get(CARD, pan)
                .then().statusCode(404).body("serviceName", equalTo("card-management"),
                        "error", equalTo("CardNotFoundException"), "message", not(blankOrNullString()));
    }

    @Test
    void reservationAndRollbackReportBalanceAndRejectDuplicates() {
        String pan = createCard(1000, 500, 800);
        var reserve = Map.of("amount", 100, "rrn", digits(12));

        given().spec(GATEWAY).body(reserve)
                .when().post(CARD + "/reserve", pan)
                .then().statusCode(200).body("pan", equalTo(pan), "availableBalance", equalTo(900));

        given().spec(GATEWAY).body(reserve)
                .when().post(CARD + "/reserve", pan)
                .then().statusCode(409).body("serviceName", equalTo("card-management"),
                        "error", equalTo("ReservationAlreadyExistsException"));

        given().spec(GATEWAY).body(Map.of("amount", 100, "rrn", reserve.get("rrn"), "pan", pan))
                .when().post(CARD + "/rollback", pan)
                .then().statusCode(200).body("pan", equalTo(pan), "availableBalance", equalTo(1000));

        given().spec(GATEWAY).body(Map.of("amount", 100, "rrn", reserve.get("rrn"), "pan", pan))
                .when().post(CARD + "/rollback", pan)
                .then().statusCode(409).body("serviceName", equalTo("card-management"),
                        "error", equalTo("RollbackAlreadySatisfiedException"));
    }

    @Test
    void reserveRejectsAmountAboveBalance() {
        String pan = createCard(1000, 500, 800);

        given().spec(GATEWAY).body(Map.of("amount", 1001, "rrn", digits(12)))
                .when().post(CARD + "/reserve", pan)
                .then().statusCode(402).body("serviceName", equalTo("card-management"),
                        "error", equalTo("InsufficientFundsException"));
    }

    @ParameterizedTest
    @CsvSource({"limit,0,400,getCards.limit", "offset,-1,400,getCards.offset",
        "limit,10001,413,can not be more than 10000", "bin,40000,400,6 digits",
        "issuerId,ABCDEFGHIJK,400,size must be between", "issuerId,abcd,400,getCards.issuerId"})
    void listRejectsInvalidParameter(String field, String value, int status, String rule) {
        given().spec(GATEWAY).queryParam(field, value)
                .when().get("/api/cards")
                .then().statusCode(status).body("serviceName", equalTo("card-management"),
                        "message", containsString(rule));
    }

    @ParameterizedTest
    @CsvSource({"0,400000,greater than or equal to 1", "1,40000,6 digits"})
    void generationRejectsInvalidRequest(int count, String bin, String rule) {
        given().spec(GATEWAY).body(Map.of("count", count, "bins", List.of(bin)))
                .when().post("/api/cards/generate")
                .then().statusCode(400).body("serviceName", equalTo("card-management"),
                        "message", containsString(rule));
    }

    @Test
    void bulkUpdateRejectsConflictingSelectors() {
        given().spec(GATEWAY).body(Map.of("bins", List.of("400000"),
                        "pans", List.of(unknownPan("400000")), "status", "ACTIVE"))
                .when().post("/api/cards/bulk-update")
                .then().statusCode(400).body("serviceName", equalTo("card-management"),
                        "message", equalTo("Only one of bin or pans must be provided"));
    }

    @ParameterizedTest
    @CsvSource({"cardholderName,lowercase,uppercase letters", "dailyLimit,-1,negative",
        "monthlyLimit,-1,negative", "bin,40000,6 digits", "bin,4000000,6 digits",
        "bin,40000X,6 digits", "currencyCode,64,exactly 3", "currencyCode,6430,exactly 3",
        "currencyCode,64X,digits only"})
    void createRejectsInvalidCardData(String field, String value, String rule) {
        var request = new HashMap<>(card(1000, 500, 800));
        request.put(field, field.endsWith("Limit") ? Integer.parseInt(value) : value);

        given().spec(GATEWAY).body(request)
                .when().post("/api/cards")
                .then().statusCode(400).body("serviceName", equalTo("card-management"),
                        "error", equalTo("MethodArgumentNotValidException"), "message", containsStringIgnoringCase(rule));
    }
}
