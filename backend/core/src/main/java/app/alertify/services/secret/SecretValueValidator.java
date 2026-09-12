package app.alertify.services.secret;

import tools.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

import app.alertify.api.error.InvalidConfigurationExpressionException;
import app.alertify.api.error.InvalidSecretValueException;
import app.alertify.configuration.service.ConfigurationExpressionParser;
import app.alertify.configuration.service.ConfigurationExpressionParser.ExpressionScope;
import app.alertify.jpa.entity.SecretValueType;
import app.alertify.worker.contract.DatabaseCredentials;

/**
 * Validates a submitted secret value against its declared type and returns the
 * canonical plaintext that gets encrypted. Size and emptiness limits are still
 * enforced by {@link SecretEncryptionService#encrypt(String)}; expression
 * references are checked for existence and cycles by
 * {@link SecretExpressionService} once the secret has an id.
 */
@Component
public class SecretValueValidator {

    private final ConfigurationExpressionParser expressionParser;

    public SecretValueValidator(ConfigurationExpressionParser expressionParser) {
        this.expressionParser = expressionParser;
    }

    public String validateAndNormalize(SecretValueType type, JsonNode value) {
        if (type == null)
            throw new InvalidSecretValueException("Secret value type is required");

        if (value == null || value.isNull() || value.isMissingNode())
            throw new InvalidSecretValueException("Secret value must not be null");

        return switch (type) {
            case STRING -> validateString(value, "STRING");
            case DB_SECRET -> validateDatabaseCredentials(value);
            case EXPRESSION -> validateExpression(validateString(value, "EXPRESSION"));
        };
    }

    /**
     * Validates a value that already arrived as plaintext, such as a writable
     * secret returned by a worker, and returns its canonical form.
     */
    public String validateAndNormalizeRaw(SecretValueType type, String raw) {
        if (type == null)
            throw new InvalidSecretValueException("Secret value type is required");

        if (raw == null)
            throw new InvalidSecretValueException("Secret value must not be null");

        return switch (type) {
            case STRING -> raw;
            case DB_SECRET -> {
                try {
                    yield DatabaseCredentials.fromJson(raw).toJson();
                } catch (IllegalArgumentException exception) {
                    throw new InvalidSecretValueException("DB_SECRET value is invalid: " + exception.getMessage());
                }
            }
            case EXPRESSION -> validateExpression(raw);
        };
    }

    private String validateExpression(String expression) {
        if (expression.isBlank())
            throw new InvalidSecretValueException("EXPRESSION value must not be blank");

        try {
            expressionParser.parse(expression, ExpressionScope.SECRET);
        } catch (InvalidConfigurationExpressionException exception) {
            throw new InvalidSecretValueException("EXPRESSION value is invalid: " + exception.getMessage());
        }
        return expression;
    }

    private static String validateString(JsonNode value, String type) {
        if (!value.isString())
            throw new InvalidSecretValueException(type + " requires a JSON string");

        return value.stringValue();
    }

    private static String validateDatabaseCredentials(JsonNode value) {
        if (!value.isObject())
            throw new InvalidSecretValueException("DB_SECRET requires a JSON object with the connection fields");

        try {
            return DatabaseCredentials.fromJson(value).toJson();
        } catch (IllegalArgumentException exception) {
            throw new InvalidSecretValueException("DB_SECRET value is invalid: " + exception.getMessage());
        }
    }
}
