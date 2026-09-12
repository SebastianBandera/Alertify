package app.alertify.configuration.service;

import java.nio.charset.StandardCharsets;

import app.alertify.api.error.InvalidConfigurationExpressionException;
import app.alertify.configuration.service.ConfigurationExpressionParser.ExpressionReference;
import app.alertify.configuration.service.ConfigurationExpressionParser.ParsedExpression;

/**
 * Walks a parsed expression, concatenating literal fragments with the values
 * produced by a {@link ReferenceResolver}, while enforcing the shared depth
 * and size limits. Utility functions receive their argument already
 * evaluated, so arguments may contain further references.
 */
public final class ExpressionEvaluator {

    public static final int MAX_DEPTH = 32;
    public static final int MAX_RESULT_BYTES = 1024 * 1024;

    private ExpressionEvaluator() {
    }

    /** Produces the value of one reference; {@code depth} is the current resolution depth. */
    @FunctionalInterface
    public interface ReferenceResolver {
        String resolve(ExpressionReference reference, String evaluatedArgument, int depth);
    }

    public static String evaluate(ParsedExpression parsed, ReferenceResolver resolver, int depth) {
        if (depth > MAX_DEPTH)
            throw new InvalidConfigurationExpressionException("Expression exceeds the maximum resolution depth of " + MAX_DEPTH);

        StringBuilder result = new StringBuilder();
        int cursor = 0;
        for (ExpressionReference reference : parsed.references()) {
            appendChecked(result, parsed.source().substring(cursor, reference.start()));
            String argument = reference.isFunction()
                    ? evaluate(reference.argument(), resolver, depth + 1)
                    : null;
            appendChecked(result, resolver.resolve(reference, argument, depth));
            cursor = reference.end();
        }
        appendChecked(result, parsed.source().substring(cursor));
        return checkedValue(result.toString(), "expression");
    }

    public static String checkedValue(String value, String source) {
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_RESULT_BYTES) {
            throw new InvalidConfigurationExpressionException(
                    "Evaluated value from '" + source + "' exceeds the 1 MiB result limit"
            );
        }
        return value;
    }

    private static void appendChecked(StringBuilder result, String value) {
        if (result.length() + value.length() > MAX_RESULT_BYTES) {
            throw new InvalidConfigurationExpressionException("Evaluated expression exceeds the 1 MiB result limit");
        }
        result.append(value);
    }
}
