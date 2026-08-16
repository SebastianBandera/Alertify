package app.alertify.configuration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import app.alertify.api.error.InvalidConfigurationValueException;
import app.alertify.jpa.entity.ConfigurationValueType;
import tools.jackson.databind.node.DecimalNode;
import tools.jackson.databind.node.IntNode;
import tools.jackson.databind.node.StringNode;

class ConfigurationValueValidatorTest {

    private final ConfigurationValueValidator validator = new ConfigurationValueValidator();

    @Test
    void normalizesDateTimeToUtcInstant() {
        var result = validator.validateAndNormalize(
            ConfigurationValueType.DATE_TIME,
            StringNode.valueOf("2026-08-15T19:30:00-03:00")
        );
        assertThat(result.stringValue()).isEqualTo("2026-08-15T22:30:00Z");
    }

    @Test
    void normalizesDecimalScale() {
        var result = validator.validateAndNormalize(
            ConfigurationValueType.DECIMAL,
            DecimalNode.valueOf(new BigDecimal("12.5000"))
        );
        assertThat(result.decimalValue()).isEqualByComparingTo("12.5");
    }

    @Test
    void rejectsValueThatDoesNotMatchDeclaredType() {
        assertThatThrownBy(() -> validator.validateAndNormalize(
            ConfigurationValueType.STRING, IntNode.valueOf(12)
        ))
            .isInstanceOf(InvalidConfigurationValueException.class)
            .hasMessage("STRING requires a JSON string");
    }
}
