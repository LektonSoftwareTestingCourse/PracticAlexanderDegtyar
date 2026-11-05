package com.processing.cardmanagement.services;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class LuhnValidatorTest {

    private final LuhnValidator validator = new LuhnValidator();

    @ParameterizedTest(name = "{0} — корректный номер")
    @ValueSource(strings = {"4111111111111111", "4012888888881881", "5500005555555559", "378282246310005"})
    void shouldAcceptPansWithCorrectCheckDigit(String pan) {

        assertThat(validator.isValid(pan)).isTrue();
    }

    @ParameterizedTest(name = "{0} — неверная контрольная цифра")
    @ValueSource(strings = {"4111111111111112", "4000001234567890", "5500005555555550"})
    void shouldRejectPansWithWrongCheckDigit(String pan) {

        assertThat(validator.isValid(pan)).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validNumberWithOneDigitChanged")
    void shouldDetectAnySingleDigitMistakeInAValidNumber(String broken) {

        assertThat(validator.isValid(broken)).isFalse();
    }

    static Stream<String> validNumberWithOneDigitChanged() {
        String valid = "4111111111111111";
        return IntStream.range(0, valid.length())
                .mapToObj(i -> valid.substring(0, i)
                        + (char) ('0' + ((valid.charAt(i) - '0' + 1) % 10))
                        + valid.substring(i + 1));
    }

    @RepeatedTest(25)
    void shouldGenerateSixteenDigitPanWithGivenBinAndValidCheckDigit() {

        String pan = validator.generatePan("400000");

        assertThat(pan).hasSize(16).containsOnlyDigits().startsWith("400000");
        assertThat(validator.isValid(pan)).isTrue();
    }
}
