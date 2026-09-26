package app.alertify.worker.contract;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.alertify.worker.grpc.ExecutionError;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Removes secret-bound values from execution diagnostics before they cross a trust boundary. */
public final class ExecutionErrorSanitizer {

    public static final String REDACTED = "[REDACTED]";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ExecutionErrorSanitizer() {
    }

    public static ExecutionError sanitize(ExecutionError error, Iterable<String> secretValues) {
        Pattern pattern = sensitivePattern(secretValues);
        if (pattern == null)
            return error;

        return error.toBuilder()
                .setMessage(sanitize(error.getMessage(), pattern))
                .setStackTrace(sanitize(error.getStackTrace(), pattern))
                .build();
    }

    public static String sanitize(String text, Iterable<String> secretValues) {
        Pattern pattern = sensitivePattern(secretValues);
        return pattern == null ? text : sanitize(text, pattern);
    }

    private static String sanitize(String text, Pattern pattern) {
        return text == null ? null : pattern.matcher(text).replaceAll(Matcher.quoteReplacement(REDACTED));
    }

    private static Pattern sensitivePattern(Iterable<String> secretValues) {
        Set<String> candidates = new LinkedHashSet<>();
        if (secretValues != null) {
            for (String value : secretValues)
                addCandidates(candidates, value);
        }
        if (candidates.isEmpty())
            return null;

        List<String> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.comparingInt(String::length).reversed());
        return Pattern.compile(ordered.stream().map(Pattern::quote).reduce((left, right) -> left + "|" + right).orElseThrow());
    }

    private static void addCandidates(Set<String> candidates, String value) {
        if (value == null || value.isEmpty())
            return;

        candidates.add(value);
        try {
            collectTextValues(JSON.readTree(value), candidates);
        } catch (RuntimeException ignored) {
            // Plain-text secrets are already represented by their complete value.
        }
    }

    private static void collectTextValues(JsonNode node, Set<String> candidates) {
        if (node == null)
            return;

        if (node.isString()) {
            if (!node.stringValue().isEmpty())
                candidates.add(node.stringValue());

            return;
        }
        if (node.isArray()) {
            for (int index = 0; index < node.size(); index++)
                collectTextValues(node.get(index), candidates);

            return;
        }
        if (node.isObject())
            node.properties().forEach(entry -> collectTextValues(entry.getValue(), candidates));
    }
}
