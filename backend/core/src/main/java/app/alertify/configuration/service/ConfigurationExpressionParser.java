package app.alertify.configuration.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import app.alertify.api.error.InvalidConfigurationExpressionException;

/**
 * Parses {@code {{configs.NAME}}}, {@code {{env.NAME}}}, {@code {{utils.NAME}}},
 * {@code {{utils.FUNCTION(argument)}}} and, for secret expressions only,
 * {@code {{secrets.NAME}}} references without evaluating them, producing the
 * dependency metadata used for validation and resolution. Delimiters may only
 * nest inside a utility function argument, which is itself an expression.
 */
@Component
public class ConfigurationExpressionParser {

    private static final Pattern ENVIRONMENT_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern UTILITY_NAME = Pattern.compile("[A-Z][A-Z0-9_]*");
    private static final Pattern UTILITY_FUNCTION = Pattern.compile("utils\\.([A-Z][A-Z0-9_]*)\\((.*)\\)", Pattern.DOTALL);
    private static final int MAX_NESTING = 8;

    public ParsedExpression parse(String expression) {
        return parse(expression, ExpressionScope.CONFIGURATION);
    }

    public ParsedExpression parse(String expression, ExpressionScope scope) {
        if (expression == null)
            throw new InvalidConfigurationExpressionException("Configuration expression must not be null");

        return parse(expression, scope, 0);
    }

    private ParsedExpression parse(String expression, ExpressionScope scope, int nesting) {
        if (nesting > MAX_NESTING)
            throw new InvalidConfigurationExpressionException("Expression exceeds the maximum function nesting of " + MAX_NESTING);

        List<ExpressionReference> references = new ArrayList<>();
        Set<String> configurationNames = new LinkedHashSet<>();
        Set<String> secretNames = new LinkedHashSet<>();
        Set<String> environmentNames = new LinkedHashSet<>();
        Set<String> utilityNames = new LinkedHashSet<>();
        Set<String> utilityFunctionNames = new LinkedHashSet<>();
        int cursor = 0;

        while (cursor < expression.length()) {
            int opening = expression.indexOf("{{", cursor);
            int unexpectedClosing = expression.indexOf("}}", cursor);
            if (unexpectedClosing >= 0 && (opening < 0 || unexpectedClosing < opening)) {
                throw new InvalidConfigurationExpressionException("Unexpected expression closing delimiter at position " + unexpectedClosing);
            }
            if (opening < 0)
                break;

            int closing = matchingClosing(expression, opening);
            String token = expression.substring(opening + 2, closing);
            ExpressionReference reference = parseReference(token, opening, closing + 2, scope, nesting);
            references.add(reference);
            collectNames(reference, configurationNames, secretNames, environmentNames, utilityNames, utilityFunctionNames);
            cursor = closing + 2;
        }

        return new ParsedExpression(
                expression,
                List.copyOf(references),
                Set.copyOf(configurationNames),
                Set.copyOf(secretNames),
                Set.copyOf(environmentNames),
                Set.copyOf(utilityNames),
                Set.copyOf(utilityFunctionNames)
        );
    }

    /** Finds the {@code }}} that closes the reference opened at {@code opening}, honouring nested delimiters. */
    private static int matchingClosing(String expression, int opening) {
        int depth = 1;
        int index = opening + 2;
        while (index < expression.length() - 1) {
            if (expression.startsWith("{{", index)) {
                depth++;
                index += 2;
            } else if (expression.startsWith("}}", index)) {
                depth--;
                if (depth == 0)
                    return index;

                index += 2;
            } else {
                index++;
            }
        }
        throw new InvalidConfigurationExpressionException("Expression opened at position " + opening + " is not closed");
    }

    private ExpressionReference parseReference(String token, int start, int end, ExpressionScope scope, int nesting) {
        Matcher function = UTILITY_FUNCTION.matcher(token);
        if (token.startsWith("utils.") && function.matches()) {
            ParsedExpression argument = parse(function.group(2), scope, nesting + 1);
            return new ExpressionReference(ReferenceType.UTILITY, function.group(1), start, end, argument);
        }
        if (token.contains("{{") || token.contains("}}")) {
            throw new InvalidConfigurationExpressionException(
                    "Nested expression delimiters are only allowed inside utils function arguments"
            );
        }
        if (token.startsWith("configs.")) {
            String name = token.substring("configs.".length());
            if (!isValidEntryName(name))
                throw new InvalidConfigurationExpressionException("Invalid configuration reference '{{" + token + "}}'");

            return new ExpressionReference(ReferenceType.CONFIGURATION, name, start, end, null);
        }
        if (token.startsWith("secrets.")) {
            if (scope != ExpressionScope.SECRET) {
                throw new InvalidConfigurationExpressionException(
                        "Unsupported expression reference '{{" + token + "}}': secrets cannot be referenced from configuration expressions"
                );
            }
            String name = token.substring("secrets.".length());
            if (!isValidEntryName(name))
                throw new InvalidConfigurationExpressionException("Invalid secret reference '{{" + token + "}}'");

            return new ExpressionReference(ReferenceType.SECRET, name, start, end, null);
        }
        if (token.startsWith("env.")) {
            String name = token.substring("env.".length());
            if (!ENVIRONMENT_NAME.matcher(name).matches()) {
                throw new InvalidConfigurationExpressionException("Invalid environment variable reference '{{" + token + "}}'");
            }
            if (EnvironmentVariableResolver.isAlwaysDenied(name)) {
                throw new InvalidConfigurationExpressionException("Environment variable '" + name + "' cannot be referenced by expressions");
            }
            return new ExpressionReference(ReferenceType.ENVIRONMENT, name, start, end, null);
        }
        if (token.startsWith("utils.")) {
            String name = token.substring("utils.".length());
            if (!UTILITY_NAME.matcher(name).matches()) {
                throw new InvalidConfigurationExpressionException("Invalid utility reference '{{" + token + "}}'");
            }
            return new ExpressionReference(ReferenceType.UTILITY, name, start, end, null);
        }
        String scopes = scope == ExpressionScope.SECRET
                ? "configs.NAME, secrets.NAME, env.NAME, utils.NAME or utils.FUNCTION(argument)"
                : "configs.NAME, env.NAME, utils.NAME or utils.FUNCTION(argument)";
        throw new InvalidConfigurationExpressionException(
                "Unsupported expression reference '{{" + token + "}}'; use " + scopes
        );
    }

    private static boolean isValidEntryName(String name) {
        return !name.isBlank() && name.equals(name.trim()) && !name.contains("{") && !name.contains("}")
                && !name.contains("(") && !name.contains(")");
    }

    private static void collectNames(ExpressionReference reference, Set<String> configurationNames, Set<String> secretNames, Set<String> environmentNames, Set<String> utilityNames, Set<String> utilityFunctionNames) {
        switch (reference.type()) {
            case CONFIGURATION -> configurationNames.add(reference.name());
            case SECRET -> secretNames.add(reference.name());
            case ENVIRONMENT -> environmentNames.add(reference.name());
            case UTILITY -> {
                if (reference.isFunction())
                    utilityFunctionNames.add(reference.name());
                else
                    utilityNames.add(reference.name());
            }
        }
        if (reference.argument() != null) {
            configurationNames.addAll(reference.argument().configurationNames());
            secretNames.addAll(reference.argument().secretNames());
            environmentNames.addAll(reference.argument().environmentNames());
            utilityNames.addAll(reference.argument().utilityNames());
            utilityFunctionNames.addAll(reference.argument().utilityFunctionNames());
        }
    }

    public record ParsedExpression(
        String source,
        List<ExpressionReference> references,
        Set<String> configurationNames,
        Set<String> secretNames,
        Set<String> environmentNames,
        Set<String> utilityNames,
        Set<String> utilityFunctionNames
    ) {
    }

    /**
     * One reference inside an expression. {@code argument} is only present for
     * utility functions and holds the parsed sub-expression between the
     * parentheses.
     */
    public record ExpressionReference(
        ReferenceType type,
        String name,
        int start,
        int end,
        ParsedExpression argument
    ) {

        public boolean isFunction() {
            return argument != null;
        }
    }

    public enum ReferenceType {
        CONFIGURATION,
        SECRET,
        ENVIRONMENT,
        UTILITY
    }

    /** Where an expression lives; only secret expressions may read other secrets. */
    public enum ExpressionScope {
        CONFIGURATION,
        SECRET
    }
}
