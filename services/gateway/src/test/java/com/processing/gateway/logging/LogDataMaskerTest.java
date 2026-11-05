package com.processing.gateway.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class LogDataMaskerTest {

    private final LogDataMasker masker = new LogDataMasker();

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "{\"pan\":\"4000001234567890\"}                  | {\"pan\":\"400000****7890\"}",
            "{\"pan\":\"4000001234567890123\"}               | {\"pan\":\"400000****0123\"}",
            "{\"PAN\" : \"4000001234567890\"}                | {\"pan\":\"400000****7890\"}",
            "{\"pan\":  \"5500005555555559\", \"amount\":1}  | {\"pan\":\"550000****5559\", \"amount\":1}"
    })
    void shouldKeepOnlyFirstSixAndLastFourDigitsOfPan(String input, String expected) {

        assertThat(masker.maskData(input)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "{\"cvv\":\"123\"}  | {\"cvv\":\"***\"}",
            "{\"cvv\":\"1234\"} | {\"cvv\":\"***\"}",
            "{\"CVV\": \"123\"} | {\"cvv\":\"***\"}"
    })
    void shouldHideCvvCompletely(String input, String expected) {

        assertThat(masker.maskData(input)).isEqualTo(expected);
    }

    @Test
    void shouldMaskAllCardNumbersInOneMessage() {

        String json = "[{\"pan\":\"4000001234567890\"},{\"pan\":\"5500005555555559\"}]";

        assertThat(masker.maskData(json))
                .isEqualTo("[{\"pan\":\"400000****7890\"},{\"pan\":\"550000****5559\"}]");
    }

    @Test
    void shouldMaskPanAndCvvTogether() {

        String json = "{\"pan\":\"4000001234567890\",\"cvv\":\"123\",\"amount\":10}";

        String masked = masker.maskData(json);

        assertThat(masked).doesNotContain("1234567890").doesNotContain("\"123\"")
                .contains("400000****7890").contains("\"cvv\":\"***\"").contains("\"amount\":10");
    }

    @Test
    void shouldLeaveDataWithoutSensitiveFieldsUntouched() {

        String json = "{\"stan\":\"000001\",\"amount\":1500,\"terminalId\":\"TERM0001\"}";

        assertThat(masker.maskData(json)).isEqualTo(json);
    }
}
