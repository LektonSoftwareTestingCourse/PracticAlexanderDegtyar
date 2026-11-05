package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationRequestNormalizerTest {

    @ParameterizedTest(name = "terminalId \"{0}\" -> \"{1}\"")
    @CsvSource({
            "TERM1,       TERM1000",
            "TERM001,     TERM0010",
            "TERM0001,    TERM0001",
            "TERM00012,   TERM0001",
            "TERM0001234, TERM0001"
    })
    void shouldPadOrTruncateTerminalIdToEightCharacters(String input, String expected) {

        String result = AuthorizationRequestNormalizer.normalizeTerminalId(input);

        assertThat(result).isEqualTo(expected).hasSize(8);
    }

    @ParameterizedTest(name = "terminalId \"{0}\" остаётся без изменений")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void shouldLeaveBlankTerminalIdUntouched(String input) {

        String result = AuthorizationRequestNormalizer.normalizeTerminalId(input);

        assertThat(result).isEqualTo(input);
    }

    @ParameterizedTest(name = "merchantId \"{0}\" -> \"{1}\"")
    @CsvSource({
            "MERCH0000000002,   MERCH0000000002",
            "MERCH00000000002,  MERCH0000000002",
            "MERCH10000000002,  MERCH1000000000",
            "ABCDE00000000002,  ABCDE0000000000",
            "MERCH00000000002X, MERCH0000000000",
            "MERCH2,            MERCH2000000000"
    })
    void shouldAdjustMerchantIdToFifteenCharacters(String input, String expected) {

        String result = AuthorizationRequestNormalizer.normalizeMerchantId(input);

        assertThat(result).isEqualTo(expected).hasSize(15);
    }

    @ParameterizedTest(name = "merchantId \"{0}\" остаётся без изменений")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void shouldLeaveBlankMerchantIdUntouched(String input) {

        String result = AuthorizationRequestNormalizer.normalizeMerchantId(input);

        assertThat(result).isEqualTo(input);
    }

    @Test
    void shouldNormalizeIdentifiersAndKeepAllOtherFieldsOfTheRequest() {

        Instant time = Instant.parse("2026-06-15T12:00:00Z");
        AuthorizationRequest request = new AuthorizationRequest(
                "0100", "000001", "4000001234567890", "000000",
                new BigDecimal("1000"), "643", time,
                "TERM1", "POS", "MERCH2", "5045", "ACQ002", "ISS001");

        AuthorizationRequest normalized = AuthorizationRequestNormalizer.normalize(request);

        assertThat(normalized.terminalId()).isEqualTo("TERM1000");
        assertThat(normalized.merchantId()).isEqualTo("MERCH2000000000");
        assertThat(normalized)
                .usingRecursiveComparison()
                .ignoringFields("terminalId", "merchantId")
                .isEqualTo(request);
    }
}
