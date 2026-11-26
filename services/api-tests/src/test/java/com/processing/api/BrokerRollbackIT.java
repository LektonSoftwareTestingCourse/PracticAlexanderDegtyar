package com.processing.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.processing.api.ApiSupport.*;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;

class BrokerRollbackIT {
    @Test
    void failedPublicationRollsBackBalanceAndLeavesNoTransaction() {
        String pan = createCard(1000, 500, 800);
        var request = transaction(pan, 100);

        given().spec(GATEWAY).body(request)
                .when().post(TRANSACTIONS)
                .then().statusCode(200).body("stan", equalTo(request.get("stan")), "status", equalTo("DECLINED"),
                        "responseCode", equalTo("96"), "declineReason", equalTo("System error"));

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(8))
                .pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
                    given().spec(CARDS)
                            .when().get(CARD, pan)
                            .then().statusCode(200).body("availableBalance", equalTo(1000));
                    given().spec(GATEWAY).queryParam("pan", pan)
                            .when().get(SEARCH)
                            .then().statusCode(200).body("total", equalTo(0), "transactions", empty());
                });
    }
}
