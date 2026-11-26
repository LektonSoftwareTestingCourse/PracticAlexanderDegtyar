package com.processing.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.processing.api.ApiSupport.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

class TransactionLoggerTest {
    @Test
    void repeatDeliveryIsIdempotentButDifferentPayloadConflicts() {
        var request = logEntry();
        store(request);

        given().spec(LOGGER).body(request)
                .when().post(LOG)
                .then().statusCode(200)
                .body("id", equalTo(request.get("id")), "amount", equalTo(100), "stan", equalTo(request.get("stan")));

        var changed = new LinkedHashMap<>(request);
        changed.put("amount", 101);
        given().spec(LOGGER).body(changed)
                .when().post(LOG)
                .then().statusCode(409)
                .body("serviceName", equalTo("transaction-logger"), "error", equalTo("Transaction conflict"),
                        "message", containsString(request.get("id").toString()));

        given().spec(GATEWAY).queryParam("pan", request.get("pan"))
                .when().get(SEARCH)
                .then().statusCode(200).body("total", equalTo(1), "transactions.id", contains(request.get("id")),
                        "transactions.amount", contains(100));
    }

    @Test
    void exportUsesCsvAndIgnoresPageWindow() {
        var request = logEntry();
        store(request);

        given().spec(GATEWAY).queryParam("pan", request.get("pan")).queryParam("offset", 1)
                .when().get(TRANSACTIONS + "/export")
                .then().statusCode(200).contentType("text/csv")
                .header("Content-Disposition", "attachment; filename=\"transactions.csv\"")
                .body(allOf(containsString(request.get("rrn").toString()),
                        containsString(request.get("stan").toString())));
    }

    @ParameterizedTest
    @CsvSource({
        "pan,status", "pan,dateFrom", "pan,dateTo", "pan,merchantId", "pan,issuerId", "pan,mcc",
        "pan,declineReason", "status,dateFrom", "status,dateTo", "status,merchantId", "status,issuerId",
        "status,mcc", "status,declineReason", "dateFrom,dateTo", "dateFrom,merchantId", "dateFrom,issuerId",
        "dateFrom,mcc", "dateFrom,declineReason", "dateTo,merchantId", "dateTo,issuerId", "dateTo,mcc",
        "dateTo,declineReason", "merchantId,issuerId", "merchantId,mcc", "merchantId,declineReason",
        "issuerId,mcc", "issuerId,declineReason", "mcc,declineReason"
    })
    void searchCombinesFilterPairs(String first, String second) {
        var target = logEntry();
        target.put("status", "DECLINED");
        target.put("declineReason", "API-" + target.get("id"));
        target.remove("authCode");
        store(target);
        String date = Instant.parse(target.get("transmissionDateTime").toString()).atOffset(ZoneOffset.UTC)
                .toLocalDate().toString();
        target.put("dateFrom", date);
        target.put("dateTo", date);

        var criteria = new LinkedHashMap<String, Object>();
        criteria.put("pan", target.get("pan"));
        criteria.put(first, target.get(first));
        criteria.put(second, target.get(second));

        given().spec(GATEWAY).queryParams(criteria)
                .when().get(SEARCH)
                .then().statusCode(200).body("total", equalTo(1), "transactions.id", contains(target.get("id")));

        if (first.equals("dateFrom") && second.equals("dateTo")) {
            criteria.put("dateFrom", "2000-01-01");
        }
        criteria.put(second, switch (second) {
            case "status" -> "APPROVED";
            case "dateFrom" -> "2099-01-01";
            case "dateTo" -> "2000-01-01";
            case "mcc" -> "9999";
            default -> "UNMATCHED";
        });
        given().spec(GATEWAY).queryParams(criteria)
                .when().get(SEARCH)
                .then().statusCode(200).body("total", equalTo(0), "transactions", empty());
    }

    @ParameterizedTest
    @CsvSource({"1,0,1", "500,0,1", "1,1,0"})
    void pageWindowDoesNotChangeTotal(int limit, int offset, int size) {
        var request = logEntry();
        store(request);

        given().spec(GATEWAY).queryParam("pan", request.get("pan"))
                .queryParam("limit", limit).queryParam("offset", offset)
                .when().get(SEARCH)
                .then().statusCode(200).body("total", equalTo(1), "transactions", hasSize(size));
    }

    @Test
    void unknownPanReturnsEmptyPage() {
        given().spec(GATEWAY).queryParam("pan", unknownPan("400000"))
                .when().get(SEARCH)
                .then().statusCode(200).body("transactions", empty(), "total", equalTo(0));
    }

    @ParameterizedTest
    @CsvSource({"limit,0,must be greater than 0", "limit,501,must not exceed 500",
        "offset,-1,must be greater than or equal to 0", "status,INVALID,Failed to convert",
        "dateFrom,invalid,Failed to convert", "dateTo,invalid,Failed to convert"})
    void invalidSearchParameterIdentifiesRule(String name, String value, String rule) {
        given().spec(GATEWAY).queryParam(name, value)
                .when().get(SEARCH)
                .then().statusCode(400).body("serviceName", equalTo("transaction-logger"),
                        "message", allOf(containsString(name), containsString(rule)));
    }

    @ParameterizedTest
    @CsvSource({"/search", "/export"})
    void reversedDateRangeIsRejected(String path) {
        given().spec(GATEWAY).queryParam("dateFrom", "2026-01-02").queryParam("dateTo", "2026-01-01")
                .when().get(TRANSACTIONS + path)
                .then().statusCode(400)
                .body("error", equalTo("Validation error"), "serviceName", equalTo("transaction-logger"));
    }

    @ParameterizedTest
    @CsvSource({"stan,12345,exactly 6 digits", "processingCode,00000x,exactly 6 digits",
        "currencyCode,64x,exactly 3 digits", "mcc,541x,exactly 4 digits",
        "amount,0,must be greater than 0", "amount,-1,must be greater than 0",
        "terminalId,TERM00001,size must be between", "mti,01000,size must be between",
        "rrn,0000000000001,size must be between", "pan,400000123456789,16 digits",
        "terminalType,12345678901,size must be between", "merchantId,MERCH00000000001,size must be between",
        "acquirerId,ACQ00000001,size must be between", "issuerId,ISS00000001,size must be between",
        "authCode,AUTH123,size must be between", "acquiringFee,-1,must be greater than or equal to 0",
        "processingTimeMs,-1,must be greater than or equal to 0"})
    void invalidLogFieldIdentifiesRule(String field, String value, String rule) {
        var request = logEntry();
        request.put(field, switch (field) {
            case "amount", "acquiringFee", "processingTimeMs" -> Integer.parseInt(value);
            default -> value;
        });

        given().spec(LOGGER).body(request)
                .when().post(LOG)
                .then().statusCode(400).body("serviceName", equalTo("transaction-logger"),
                        "message", allOf(containsString(field), containsString(rule)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "mti", "stan", "pan", "processingCode", "amount", "currencyCode",
        "terminalId", "merchantId", "mcc", "acquirerId", "status", "transmissionDateTime", "createdAt"})
    void internalLogRequiresField(String field) {
        var request = logEntry();
        request.remove(field);

        given().spec(LOGGER).body(request)
                .when().post(LOG)
                .then().statusCode(400).body("serviceName", equalTo("transaction-logger"),
                        "message", allOf(containsString(field + ":"), containsString("must not be")));
    }

    private void store(Map<String, Object> request) {
        given().spec(LOGGER).body(request)
                .when().post(LOG)
                .then().statusCode(201).body("id", equalTo(request.get("id")));
    }
}
