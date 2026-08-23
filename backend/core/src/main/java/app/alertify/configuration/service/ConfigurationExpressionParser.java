package app.alertify.configuration.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import app.alertify.api.error.InvalidConfigurationExpressionException;

/**
 * Parses {@code {{configs.NAME}}} and {@code {{env.NAME}}} references without
 * evaluating them, producing the dependency metadata used for validation and
 * resolution.
 */
@Component
public class ConfigurationExpressionParser {

    private static final Pattern ENVIRONMENT_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public ParsedExpression parse(String expression) {
        if (expression == null)
            throw new InvalidConfigurationExpressionException("Configuration expression must not be null");

        List<ExpressionReference> references = new ArrayList<>();
        Set<String> configurationNames = new LinkedHashSet<>();
        Set<String> environmentNames = new LinkedHashSet<>();
        int cursor = 0;

        while (cursor < expression.length()) {
            int opening = expression.indexOf("{{", cursor);
            int unexpectedClosing = expression.indexOf("}}", cursor);
            if (unexpectedClosing >= 0 && (opening < 0 || unexpectedClosing < opening)) {
                throw new InvalidConfigurationExpressionException("Unexpected expression closing delimiter at position " + unexpectedClosing);
            }
            if (opening < 0)
                break;

            int closing = expression.indexOf("}}", opening + 2);
            if (closing < 0) {
                throw new InvalidConfigurationExpressionException("Expression opened at position " + opening + " is not closed");
            }
            if (expression.indexOf("{{", opening + 2) >= 0 && expression.indexOf("{{", opening + 2) < closing) {
                throw new InvalidConfigurationExpressionException("Nested expression delimiters are not allowed");
            }

            String token = expression.substring(opening + 2, closing);
            ExpressionReference reference = parseReference(token, opening, closing + 2);
            references.add(reference);
            if (reference.type() == ReferenceType.CONFIGURATION)
                configurationNames.add(reference.name());
            else
                environmentNames.add(reference.name());
            cursor = closing + 2;
        }

        return new ParsedExpression(
                expression, List.copyOf(references), Set.copyOf(configurationNames), Set.copyOf(environmentNames)
        );
    }

    private static ExpressionReference parseReference(String token, int start, int end) {
        if (token.startsWith("configs.")) {
            String name = token.substring("configs.".length());
            if (name.isBlank() || !name.equals(name.trim()) || name.contains("{") || name.contains("}")) {
                throw new InvalidConfigurationExpressionException("Invalid configuration reference '{{" + token + "}}'");
            }
            if (SystemConfigurationPolicy.isValueHidden(name)) {
                throw new InvalidConfigurationExpressionException("Configuration '" + SystemConfigurationPolicy.KEY_PART + "' cannot be referenced by expressions");
            }
            return new ExpressionReference(ReferenceType.CONFIGURATION, name, start, end);
        }
        if (token.startsWith("env.")) {
            String name = token.substring("env.".length());
            if (!ENVIRONMENT_NAME.matcher(name).matches()) {
                throw new InvalidConfigurationExpressionException("Invalid environment variable reference '{{" + token + "}}'");
            }
            if (EnvironmentVariableResolver.isAlwaysDenied(name)) {
                throw new InvalidConfigurationExpressionException("Environment variable '" + name + "' cannot be referenced by expressions");
            }
            return new ExpressionReference(ReferenceType.ENVIRONMENT, name, start, end);
        }
        throw new InvalidConfigurationExpressionException(
                "Unsupported expression reference '{{" + token + "}}'; use configs.NAME or env.NAME"
        );
    }

    public record ParsedExpression(
        String source,
        List<ExpressionReference> references,
        Set<String> configurationNames,
        Set<String> environmentNames
    ) {
    }

    public record ExpressionReference(
        ReferenceType type,
        String name,
        int start,
        int end
    ) {
    }

    public enum ReferenceType {
        CONFIGURATION,
        ENVIRONMENT
    }
}
