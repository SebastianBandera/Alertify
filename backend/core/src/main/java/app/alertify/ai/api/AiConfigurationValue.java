package app.alertify.ai.api;

import java.math.BigDecimal;
import java.math.BigInteger;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.BigIntegerNode;
import tools.jackson.databind.node.BooleanNode;
import tools.jackson.databind.node.DecimalNode;
import tools.jackson.databind.node.StringNode;

import app.alertify.jpa.entity.ConfigurationValueType;

/** Strict-schema representation of every non-binary application configuration value. */
public record AiConfigurationValue(
    String stringValue,
    String expressionValue,
    BigInteger integerValue,
    BigDecimal decimalValue,
    Boolean booleanValue,
    String dateValue,
    String timeValue,
    String dateTimeValue,
    String jsonValue
) {

    public JsonNode toJsonNode(ConfigurationValueType valueType, JsonMapper json) {
        if (valueType == null)
            throw new IllegalArgumentException("Configuration value type is required");

        return switch (valueType) {
            case STRING -> StringNode.valueOf(required(stringValue, "stringValue", valueType));
            case EXPRESSION -> StringNode.valueOf(required(expressionValue, "expressionValue", valueType));
            case INTEGER -> BigIntegerNode.valueOf(required(integerValue, "integerValue", valueType));
            case DECIMAL -> DecimalNode.valueOf(required(decimalValue, "decimalValue", valueType));
            case BOOLEAN -> BooleanNode.valueOf(required(booleanValue, "booleanValue", valueType));
            case DATE -> StringNode.valueOf(required(dateValue, "dateValue", valueType));
            case TIME -> StringNode.valueOf(required(timeValue, "timeValue", valueType));
            case DATE_TIME -> StringNode.valueOf(required(dateTimeValue, "dateTimeValue", valueType));
            case JSON -> parseJson(json, required(jsonValue, "jsonValue", valueType));
            case BINARY -> throw new IllegalArgumentException("Binary configuration operations are not available to AI tools");
        };
    }

    private static <T> T required(T value, String field, ConfigurationValueType valueType) {
        if (value == null)
            throw new IllegalArgumentException(field + " must be supplied for " + valueType);

        return value;
    }

    private static JsonNode parseJson(JsonMapper json, String value) {
        try {
            return json.readTree(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("jsonValue must contain valid JSON", exception);
        }
    }
}
