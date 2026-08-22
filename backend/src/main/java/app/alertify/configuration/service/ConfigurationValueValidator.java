package app.alertify.configuration.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.BigIntegerNode;
import tools.jackson.databind.node.BooleanNode;
import tools.jackson.databind.node.DecimalNode;
import tools.jackson.databind.node.StringNode;

import org.springframework.stereotype.Component;

import app.alertify.api.error.InvalidConfigurationValueException;
import app.alertify.jpa.entity.ConfigurationValueType;

@Component
public class ConfigurationValueValidator {

    public JsonNode validateAndNormalize(ConfigurationValueType type, JsonNode value) {
        if (type == null)
            throw new InvalidConfigurationValueException("Configuration value type is required");
        if (value == null || value.isNull() || value.isMissingNode()) {
            throw new InvalidConfigurationValueException("Configuration value must not be null");
        }

        return switch (type) {
            case STRING -> validateString(value, "STRING");
            case EXPRESSION -> validateString(value, "EXPRESSION");
            case INTEGER -> validateInteger(value);
            case DECIMAL -> validateDecimal(value);
            case BOOLEAN -> validateBoolean(value);
            case DATE -> validateDate(value);
            case DATE_TIME -> validateDateTime(value);
            case JSON -> validateJson(value);
        };
    }

    private static JsonNode validateString(JsonNode value, String type) {
        require(value.isString(), type + " requires a JSON string");
        return StringNode.valueOf(value.stringValue());
    }

    private static JsonNode validateInteger(JsonNode value) {
        require(value.isIntegralNumber(), "INTEGER requires an integral JSON number");
        return BigIntegerNode.valueOf(value.bigIntegerValue());
    }

    private static JsonNode validateDecimal(JsonNode value) {
        require(value.isNumber(), "DECIMAL requires a JSON number");
        BigDecimal normalized = value.decimalValue().stripTrailingZeros();
        return DecimalNode.valueOf(normalized);
    }

    private static JsonNode validateBoolean(JsonNode value) {
        require(value.isBoolean(), "BOOLEAN requires a JSON boolean");
        return BooleanNode.valueOf(value.booleanValue());
    }

    private static JsonNode validateDate(JsonNode value) {
        require(value.isString(), "DATE requires an ISO-8601 JSON string");
        try {
            return StringNode.valueOf(LocalDate.parse(value.stringValue()).toString());
        } catch (RuntimeException exception) {
            throw new InvalidConfigurationValueException("DATE must use ISO-8601 format yyyy-MM-dd", exception);
        }
    }

    private static JsonNode validateDateTime(JsonNode value) {
        require(value.isString(), "DATE_TIME requires an ISO-8601 JSON string with offset");
        try {
            return StringNode.valueOf(OffsetDateTime.parse(value.stringValue()).toInstant().toString());
        } catch (RuntimeException exception) {
            throw new InvalidConfigurationValueException(
                    "DATE_TIME must use ISO-8601 format and include an offset", exception
            );
        }
    }

    private static JsonNode validateJson(JsonNode value) {
        require(value.isObject() || value.isArray(), "JSON requires a JSON object or array");
        return value.deepCopy();
    }

    private static void require(boolean condition, String message) {
        if (!condition)
            throw new InvalidConfigurationValueException(message);
    }
}
