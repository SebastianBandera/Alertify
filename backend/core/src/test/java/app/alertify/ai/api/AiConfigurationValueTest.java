package app.alertify.ai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import app.alertify.jpa.entity.ConfigurationValueType;

class AiConfigurationValueTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void convertsEveryNonBinaryConfigurationType() {
        assertThat(value("text", null, null, null, null, null, null, null, null)
                .toJsonNode(ConfigurationValueType.STRING, JSON).stringValue()).isEqualTo("text");
        assertThat(value(null, "${config.name}", null, null, null, null, null, null, null)
                .toJsonNode(ConfigurationValueType.EXPRESSION, JSON).stringValue()).isEqualTo("${config.name}");
        assertThat(value(null, null, new BigInteger("12345678901234567890"), null, null, null, null, null, null)
                .toJsonNode(ConfigurationValueType.INTEGER, JSON).bigIntegerValue()).isEqualTo(new BigInteger("12345678901234567890"));
        assertThat(value(null, null, null, new BigDecimal("12.50"), null, null, null, null, null)
                .toJsonNode(ConfigurationValueType.DECIMAL, JSON).decimalValue()).isEqualByComparingTo("12.50");
        assertThat(value(null, null, null, null, true, null, null, null, null)
                .toJsonNode(ConfigurationValueType.BOOLEAN, JSON).booleanValue()).isTrue();
        assertThat(value(null, null, null, null, null, "2026-10-11", null, null, null)
                .toJsonNode(ConfigurationValueType.DATE, JSON).stringValue()).isEqualTo("2026-10-11");
        assertThat(value(null, null, null, null, null, null, "13:45", null, null)
                .toJsonNode(ConfigurationValueType.TIME, JSON).stringValue()).isEqualTo("13:45");
        assertThat(value(null, null, null, null, null, null, null, "2026-10-11T13:45:00-03:00", null)
                .toJsonNode(ConfigurationValueType.DATE_TIME, JSON).stringValue()).isEqualTo("2026-10-11T13:45:00-03:00");
        JsonNode json = value(null, null, null, null, null, null, null, null, "{\"enabled\":true}")
                .toJsonNode(ConfigurationValueType.JSON, JSON);
        assertThat(json.get("enabled").booleanValue()).isTrue();
    }

    @Test
    void usesOnlyTheFieldSelectedByValueType() {
        AiConfigurationValue strictPayload = value("text", "", BigInteger.ZERO, BigDecimal.ZERO, false, "", "", "", "");

        assertThat(strictPayload.toJsonNode(ConfigurationValueType.STRING, JSON).stringValue()).isEqualTo("text");
        assertThat(strictPayload.toJsonNode(ConfigurationValueType.INTEGER, JSON).bigIntegerValue()).isZero();
        assertThat(strictPayload.toJsonNode(ConfigurationValueType.BOOLEAN, JSON).booleanValue()).isFalse();
    }

    @Test
    void rejectsMissingInvalidAndBinaryValues() {
        AiConfigurationValue empty = value(null, null, null, null, null, null, null, null, null);
        AiConfigurationValue invalidJson = value(null, null, null, null, null, null, null, null, "{");

        assertThatThrownBy(() -> empty.toJsonNode(ConfigurationValueType.STRING, JSON))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("stringValue");
        assertThatThrownBy(() -> invalidJson.toJsonNode(ConfigurationValueType.JSON, JSON))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("valid JSON");
        assertThatThrownBy(() -> empty.toJsonNode(ConfigurationValueType.BINARY, JSON))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Binary");
    }

    private static AiConfigurationValue value(String stringValue, String expressionValue, BigInteger integerValue,
            BigDecimal decimalValue, Boolean booleanValue, String dateValue, String timeValue, String dateTimeValue,
            String jsonValue) {
        return new AiConfigurationValue(stringValue, expressionValue, integerValue, decimalValue, booleanValue,
                dateValue, timeValue, dateTimeValue, jsonValue);
    }
}
