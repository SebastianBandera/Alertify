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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Removes secret-bound values from execution outputs before they cross a trust boundary. */
public final class SecretValueSanitizer {

    public static final String REDACTED = "[REDACTED]";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern AUTHORIZATION = Pattern.compile("(?i)^(?:Authorization\\s*:\\s*)?(?:Bearer|Basic)\\s+(.+)$");

    private SecretValueSanitizer() {
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

    public static JsonNode sanitize(JsonNode node, Iterable<String> secretValues) {
        Pattern pattern = sensitivePattern(secretValues);
        return pattern == null || node == null ? node : sanitize(node, pattern);
    }

    private static String sanitize(String text, Pattern pattern) {
        return text == null ? null : pattern.matcher(text).replaceAll(Matcher.quoteReplacement(REDACTED));
    }

    private static JsonNode sanitize(JsonNode node, Pattern pattern) {
        if (node.isString())
            return JSON.valueToTree(sanitize(node.stringValue(), pattern));

        if (node.isArray()) {
            ArrayNode sanitized = JSON.createArrayNode();
            for (int index = 0; index < node.size(); index++)
                sanitized.add(sanitize(node.get(index), pattern));

            return sanitized;
        }
        if (node.isObject()) {
            ObjectNode sanitized = JSON.createObjectNode();
            node.properties().forEach(entry -> sanitized.set(entry.getKey(), sanitize(entry.getValue(), pattern)));
            return sanitized;
        }
        return node.deepCopy();
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

        addTextCandidate(candidates, value);
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
                addTextCandidate(candidates, node.stringValue());

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

    private static void addTextCandidate(Set<String> candidates, String value) {
        candidates.add(value);
        Matcher header = AUTHORIZATION.matcher(value);
        if (header.matches())
            candidates.add(header.group(1));
    }
}
