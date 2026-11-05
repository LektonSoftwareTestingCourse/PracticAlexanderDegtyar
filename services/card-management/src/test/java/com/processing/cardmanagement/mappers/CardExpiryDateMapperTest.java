package com.processing.cardmanagement.mappers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.DateTimeException;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardExpiryDateMapperTest {

    private final CardExpiryDateMapper mapper = new CardExpiryDateMapper();

    @ParameterizedTest(name = "{0}-{1} -> {2}")
    @CsvSource({
            "2029, 10, 1029",
            "2030, 3,  0330",
            "2027, 12, 1227",
            "2100, 1,  0100"
    })
    void shouldFormatYearMonthAsFourDigitsMonthFirst(int year, int month, String expected) {

        String formatted = mapper.asString(YearMonth.of(year, month));

        assertThat(formatted).isEqualTo(expected).hasSize(4);
    }

    @Test
    void shouldFormatMissingDateAsNull() {

        assertThat(mapper.asString(null)).isNull();
    }

    @ParameterizedTest(name = "{0} -> {1}-{2}")
    @CsvSource({"1029, 2029, 10", "0330, 2030, 3", "1227, 2027, 12"})
    void shouldParseFourDigitStringBackToYearMonth(String text, int year, int month) {

        assertThat(mapper.asYearMonth(text)).isEqualTo(YearMonth.of(year, month));
    }

    @ParameterizedTest(name = "\"{0}\" -> null")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void shouldParseBlankStringAsNull(String text) {

        assertThat(mapper.asYearMonth(text)).isNull();
    }

    @ParameterizedTest(name = "\"{0}\" -> ошибка")
    @ValueSource(strings = {"1329", "0029", "10-29", "102029"})
    void shouldRejectMalformedExpiryDate(String text) {

        assertThatThrownBy(() -> mapper.asYearMonth(text)).isInstanceOf(DateTimeException.class);
    }
}
