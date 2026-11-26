package com.processing.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static com.processing.api.ApiSupport.*;
import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.*;

class GatewayReadTest {
    @Test
    void healthyGatewayMatchesHealthResponseSchema() {
        given().spec(GATEWAY)
                .when().get("/health")
                .then().statusCode(200).contentType("application/json")
                .body(matchesJsonSchemaInClasspath("gateway-health.json"))
                .body("status", equalTo("ok"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/v3/api-docs", "/switch-docs", "/logger-docs", "/terminal-docs", "/merchant-docs", "/cards-docs"})
    void swaggerRouteServesOpenApi(String path) {
        given().spec(GATEWAY)
                .when().get(path)
                .then().statusCode(200).body("openapi", matchesPattern("3\\.[0-9].*"),
                        "paths", notNullValue(), "info.title", not(blankOrNullString()));
    }

    @Test
    void swaggerUiRedirectsToItsIndex() {
        given().spec(GATEWAY).redirects().follow(false)
                .when().get("/docs")
                .then().statusCode(302).header("Location", containsString("/swagger-ui/index.html"));
    }

    @Test
    void dashboardStatsReturnCounters() {
        given().spec(GATEWAY)
                .when().get("/api/dashboard/stats")
                .then().statusCode(200)
                .body("totalTransactions", greaterThanOrEqualTo(0), "approvedCount", greaterThanOrEqualTo(0),
                        "declinedCount", greaterThanOrEqualTo(0), "approvalRate", notNullValue());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 500})
    void recentRespectsLimit(int limit) {
        given().spec(GATEWAY).queryParam("limit", limit)
                .when().get("/api/dashboard/recent")
                .then().statusCode(200).body("", hasSize(lessThanOrEqualTo(limit)));
    }

    @ParameterizedTest
    @CsvSource({"0,limit must be positive", "501,limit must not exceed 500"})
    void recentRejectsLimitOutsideRange(int limit, String rule) {
        given().spec(GATEWAY).queryParam("limit", limit)
                .when().get("/api/dashboard/recent")
                .then().statusCode(400).body("serviceName", equalTo("transaction-logger"),
                        "message", containsString(rule));
    }

    @ParameterizedTest
    @ValueSource(strings = {"hour", "day"})
    void chartsReturnEmptyArrayForEmptyInterval(String granularity) {
        given().spec(GATEWAY).queryParam("granularity", granularity)
                .queryParam("from", "2000-01-01T00:00:00Z").queryParam("to", "2000-01-02T00:00:00Z")
                .when().get("/api/dashboard/charts")
                .then().statusCode(200).body("", empty());
    }

    @Test
    void chartsRejectsUnsupportedGranularity() {
        given().spec(GATEWAY).queryParam("granularity", "month")
                .when().get("/api/dashboard/charts")
                .then().statusCode(400).body("serviceName", equalTo("transaction-logger"),
                        "message", containsString("granularity must be hour or day"));
    }
}
