package com.processing.service;

import com.processing.config.SwitchProperties;
import com.processing.exception.UnknownBinException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingServiceTest {

    private RoutingService routingService;

    @BeforeEach
    void setUp() {
        SwitchProperties properties = new SwitchProperties(
                "1.0.0",
                Map.of("400000", "ISS001", "400001", "ISS002", "400004", "ISS005"),
                null, null, null, null, null, null);
        routingService = new RoutingService(properties);
    }

    @ParameterizedTest(name = "PAN {0} -> {1}")
    @CsvSource({
            "4000001234567890, ISS001",
            "4000011234567890, ISS002",
            "4000041234567890, ISS005",
            "400000,           ISS001"
    })
    void shouldResolveIssuerByFirstSixDigits(String pan, String expectedIssuer) {

        String issuerId = routingService.getIssuerIdByPan(pan);

        assertThat(issuerId).isEqualTo(expectedIssuer);
    }

    @ParameterizedTest(name = "PAN {0}")
    @ValueSource(strings = {"9999991234567890", "4000021234567890", "5555555555554444"})
    void shouldRejectBinMissingFromRoutingTable(String pan) {

        assertThatThrownBy(() -> routingService.getIssuerIdByPan(pan))
                .isInstanceOf(UnknownBinException.class)
                .hasMessageContaining(pan.substring(0, 6));
    }

    @ParameterizedTest(name = "PAN \"{0}\"")
    @ValueSource(strings = {"", "4", "40000"})
    @NullSource
    void shouldRejectPanShorterThanBin(String pan) {

        assertThatThrownBy(() -> routingService.getIssuerIdByPan(pan))
                .isInstanceOf(UnknownBinException.class)
                .hasMessageContaining("null-or-short");
    }

    @Test
    void shouldNotSeeRoutingTableChangesMadeAfterConstruction() {

        java.util.HashMap<String, String> mutable = new java.util.HashMap<>(Map.of("400000", "ISS001"));
        RoutingService service = new RoutingService(
                new SwitchProperties("1", mutable, null, null, null, null, null, null));

        mutable.put("400009", "ISS009");

        assertThatThrownBy(() -> service.getIssuerIdByPan("4000091234567890"))
                .isInstanceOf(UnknownBinException.class);
    }
}
