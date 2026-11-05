package com.processing.gateway.validation;

import com.processing.common.dto.authorization.AuthorizationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionRequestValidatorTest {

    private static final Instant TIME = Instant.parse("2026-06-15T12:00:00Z");

    private final TransactionRequestValidator validator = new TransactionRequestValidator();

    @Test
    void shouldAcceptFullyValidRequest() {

        assertThatCode(() -> validator.validate(valid())).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectMissingBody() {

        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Request body is required");
    }

    @ParameterizedTest(name = "поле {0} пустое")
    @ValueSource(strings = {"mti", "stan", "pan", "processingCode", "currencyCode", "terminalId",
            "merchantId", "mcc", "acquirerId"})
    void shouldRejectBlankRequiredTextField(String field) {

        AuthorizationRequest request = withBlank(field, "   ");

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field '" + field + "' is required");
    }

    @ParameterizedTest(name = "поле {0} отсутствует")
    @ValueSource(strings = {"mti", "stan", "pan", "processingCode", "currencyCode", "terminalId",
            "merchantId", "mcc", "acquirerId"})
    void shouldRejectNullRequiredTextField(String field) {

        AuthorizationRequest request = withBlank(field, null);

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field '" + field + "' is required");
    }

    @Test
    void shouldRejectMissingTransmissionDateTime() {

        AuthorizationRequest request = new AuthorizationRequest(
                "0100", "000001", "4000001234567890", "000000", BigDecimal.TEN, "643", null,
                "TERM0001", "POS", "MERCH0000000002", "5045", "ACQ002", "ISS001");

        assertThatThrownBy(() -> validator.validate(request))
                .hasMessage("Field 'transmissionDateTime' is required");
    }

    @ParameterizedTest(name = "mti={0}")
    @ValueSource(strings = {"0400", "0200", "0110", "100", "01000"})
    void shouldAcceptOnlyAuthorizationRequestMti(String mti) {

        AuthorizationRequest request = request(mti, "4000001234567890", BigDecimal.TEN, "643", "5045");

        assertThatThrownBy(() -> validator.validate(request)).hasMessage("Field 'mti' must be '0100'");
    }

    @ParameterizedTest(name = "pan=\"{0}\"")
    @ValueSource(strings = {"400000123456789", "40000012345678901", "4000001234567ABC", "4000 0012 3456 789",
            "4000001234567-90"})
    void shouldRejectPanThatIsNotExactlySixteenDigits(String pan) {

        AuthorizationRequest request = request("0100", pan, BigDecimal.TEN, "643", "5045");

        assertThatThrownBy(() -> validator.validate(request))
                .hasMessage("Field 'pan' must be exactly 16 digits");
    }

    @ParameterizedTest(name = "amount={0} -> принято")
    @CsvSource({"0.01", "1", "1500.50", "999999999"})
    void shouldAcceptPositiveAmount(BigDecimal amount) {

        AuthorizationRequest request = request("0100", "4000001234567890", amount, "643", "5045");

        assertThatCode(() -> validator.validate(request)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "amount={0} -> отказ")
    @CsvSource({"0", "0.00", "-0.01", "-1", "-1500"})
    void shouldRejectZeroOrNegativeAmount(BigDecimal amount) {

        AuthorizationRequest request = request("0100", "4000001234567890", amount, "643", "5045");

        assertThatThrownBy(() -> validator.validate(request)).hasMessage("Field 'amount' must be > 0");
    }

    @Test
    void shouldRejectMissingAmount() {

        AuthorizationRequest request = request("0100", "4000001234567890", null, "643", "5045");

        assertThatThrownBy(() -> validator.validate(request)).hasMessage("Field 'amount' is required");
    }

    @ParameterizedTest(name = "currencyCode=\"{0}\"")
    @ValueSource(strings = {"64", "6430", "RU", "RUBL"})
    void shouldRejectCurrencyCodeThatIsNotThreeCharacters(String currency) {

        AuthorizationRequest request = request("0100", "4000001234567890", BigDecimal.TEN, currency, "5045");

        assertThatThrownBy(() -> validator.validate(request))
                .hasMessage("Field 'currencyCode' must be exactly 3 characters");
    }

    @ParameterizedTest(name = "mcc=\"{0}\"")
    @ValueSource(strings = {"504", "50455", "ABCD", "50 4", "-504"})
    void shouldRejectMccThatIsNotFourDigits(String mcc) {

        AuthorizationRequest request = request("0100", "4000001234567890", BigDecimal.TEN, "643", mcc);

        assertThatThrownBy(() -> validator.validate(request))
                .hasMessage("Field 'mcc' must be exactly 4 digits");
    }

    @Test
    void shouldReportMissingRequiredFieldBeforeFormatErrors() {

        AuthorizationRequest request = new AuthorizationRequest(
                "0400", "000001", "4000001234567890", "000000", BigDecimal.TEN, "643", TIME,
                "", "POS", "MERCH0000000002", "5045", "ACQ002", "ISS001");

        assertThatThrownBy(() -> validator.validate(request)).hasMessage("Field 'terminalId' is required");
    }

    @Test
    void shouldReportMtiBeforeAmountWhenBothAreWrong() {

        AuthorizationRequest request = request("0400", "4000001234567890", BigDecimal.ZERO, "643", "5045");

        assertThatThrownBy(() -> validator.validate(request)).hasMessage("Field 'mti' must be '0100'");
    }

    private AuthorizationRequest valid() {
        return request("0100", "4000001234567890", new BigDecimal("1500"), "643", "5045");
    }

    private AuthorizationRequest request(String mti, String pan, BigDecimal amount, String currency, String mcc) {
        return new AuthorizationRequest(
                mti, "000001", pan, "000000", amount, currency, TIME,
                "TERM0001", "POS", "MERCH0000000002", mcc, "ACQ002", "ISS001");
    }

    private AuthorizationRequest withBlank(String field, String value) {
        return new AuthorizationRequest(
                pick("mti", field, value, "0100"),
                pick("stan", field, value, "000001"),
                pick("pan", field, value, "4000001234567890"),
                pick("processingCode", field, value, "000000"),
                new BigDecimal("1500"),
                pick("currencyCode", field, value, "643"),
                TIME,
                pick("terminalId", field, value, "TERM0001"),
                "POS",
                pick("merchantId", field, value, "MERCH0000000002"),
                pick("mcc", field, value, "5045"),
                pick("acquirerId", field, value, "ACQ002"),
                "ISS001");
    }

    private String pick(String name, String blankField, String blankValue, String defaultValue) {
        return name.equals(blankField) ? blankValue : defaultValue;
    }
}
