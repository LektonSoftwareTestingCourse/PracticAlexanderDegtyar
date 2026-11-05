package com.processing.gateway.downstream;

import com.processing.gateway.properties.GatewayRouteProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DownstreamServiceResolverTest {

    private DownstreamServiceResolver resolver;

    @BeforeEach
    void setUp() {
        GatewayRouteProperties properties = new GatewayRouteProperties();
        properties.setRoutes(List.of(
                route("switch", "Path=/api/transactions", "Method=POST"),
                route("logger", "Path=/api/transactions/**"),
                route("logger", "Path=/api/dashboard/**"),
                route("cardManagement", "Path=/api/cards/**, /cards-docs"),
                routeWithoutServiceName("Path=/health/**"),
                route("terminalSimulator", "Method=POST")
        ));
        resolver = new DownstreamServiceResolver(properties);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "/api/transactions,          switch",
            "/api/transactions/search,   logger",
            "/api/transactions/export,   logger",
            "/api/dashboard/stats,       logger",
            "/api/cards/4000001234567890, cardManagement",
            "/api/cards,                 cardManagement",
            "/cards-docs,                cardManagement"
    })
    void shouldResolveServiceByFirstMatchingRoutePath(String path, String expectedService) {

        assertThat(resolver.resolve(path)).contains(expectedService);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"/unknown", "/api/other/thing", "/health/live", "/"})
    void shouldReturnNothingForPathsWithoutServiceMapping(String path) {

        assertThat(resolver.resolve(path)).isEmpty();
    }

    private GatewayRouteProperties.RouteDefinition route(String serviceName, String... predicates) {
        GatewayRouteProperties.RouteDefinition route = new GatewayRouteProperties.RouteDefinition();
        route.setMetadata(Map.of("serviceName", serviceName));
        route.setPredicates(List.of(predicates));
        return route;
    }

    private GatewayRouteProperties.RouteDefinition routeWithoutServiceName(String... predicates) {
        GatewayRouteProperties.RouteDefinition route = new GatewayRouteProperties.RouteDefinition();
        route.setPredicates(List.of(predicates));
        return route;
    }
}
