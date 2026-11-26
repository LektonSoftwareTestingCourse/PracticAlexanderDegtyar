package com.processing.api;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;

final class ApiSupport {
    static final RequestSpecification GATEWAY = service("gateway", 8080);
    static final RequestSpecification SWITCH = service("switch", 8082);
    static final RequestSpecification AUTH = service("auth", 8083);
    static final RequestSpecification LOGGER = service("logger", 8088);
    static final RequestSpecification CARDS = service("cards", 8081);
    static final String CARD = "/api/cards/{pan}";
    static final String TRANSACTIONS = "/api/transactions";
    static final String SEARCH = TRANSACTIONS + "/search";
    static final String AUTHORIZE = "/api/internal/authorize";
    static final String LOG = "/api/internal/log";

    private ApiSupport() {
    }

    static RequestSpecification service(String name, int port) {
        return new RequestSpecBuilder()
                .setBaseUri(System.getProperty("api." + name, "http://localhost:" + port))
                .setContentType(ContentType.JSON)
                .build();
    }

    static Map<String, Object> card(int balance, int daily, int monthly) {
        return Map.of("bin", "400000", "cardholderName", "API TEST", "currencyCode", "643",
                "initialBalance", balance, "dailyLimit", daily, "monthlyLimit", monthly);
    }

    static String createCard(int balance, int daily, int monthly) {
        return given().spec(GATEWAY).body(card(balance, daily, monthly))
                .when().post("/api/cards")
                .then().statusCode(201).body("status", equalTo("ACTIVE"))
                .body("availableBalance", equalTo(balance))
                .body("pan", matchesPattern("400000[0-9]{10}")).extract().path("pan");
    }

    static String unknownPan(String bin) {
        return bin + digits(10);
    }

    static String digits(int length) {
        long limit = (long) Math.pow(10, length);
        return String.format(Locale.ROOT, "%0" + length + "d", ThreadLocalRandom.current().nextLong(limit));
    }

    static Map<String, Object> transaction(String pan, int amount) {
        var data = new LinkedHashMap<String, Object>();
        data.put("mti", "0100");
        data.put("stan", digits(6));
        data.put("pan", pan);
        data.put("processingCode", "000000");
        data.put("amount", amount);
        data.put("currencyCode", "643");
        data.put("transmissionDateTime", now());
        data.put("terminalId", "TERM0001");
        data.put("terminalType", "POS");
        data.put("merchantId", "MERCH0000000001");
        data.put("mcc", "5411");
        data.put("acquirerId", "ACQ001");
        data.put("issuerId", "ISS001");
        return data;
    }

    static Map<String, Object> logEntry() {
        var data = transaction(unknownPan("400000"), 100);
        data.put("id", UUID.randomUUID().toString());
        data.put("createdAt", now());
        data.put("rrn", digits(12));
        data.put("status", "APPROVED");
        data.put("authCode", "A12345");
        data.put("processingTimeMs", 1);
        return data;
    }

    static String now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }
}
