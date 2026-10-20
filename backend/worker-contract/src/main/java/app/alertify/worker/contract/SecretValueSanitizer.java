package app.alertify.worker.contract;

import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.util.Base64;
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
        Protection protection = protection(secretValues);
        return error.toBuilder()
                .setMessage(protection.sanitize(error.getMessage()))
                .setStackTrace(protection.sanitize(error.getStackTrace()))
                .build();
    }

    public static String sanitize(String text, Iterable<String> secretValues) {
        return protection(secretValues).sanitize(text);
    }

    public static JsonNode sanitize(JsonNode node, Iterable<String> secretValues) {
        return protection(secretValues).sanitize(node);
    }

    private static String sanitize(String text, Pattern pattern) {
        return text == null ? null : pattern.matcher(text).replaceAll(Matcher.quoteReplacement(REDACTED));
    }

    private static JsonNode sanitize(JsonNode node, Protection protection) {
        if (node.isString())
            return JSON.valueToTree(protection.sanitize(node.stringValue()));

        if (node.isArray()) {
            ArrayNode sanitized = JSON.createArrayNode();
            for (int index = 0; index < node.size(); index++)
                sanitized.add(sanitize(node.get(index), protection));

            return sanitized;
        }
        if (node.isObject()) {
            ObjectNode sanitized = JSON.createObjectNode();
            node.properties().forEach(entry -> sanitized.set(entry.getKey(), sanitize(entry.getValue(), protection)));
            return sanitized;
        }
        return node.deepCopy();
    }

    /** A private, execution-scoped compiled filter; never cache these values globally or log it. */
    public static Protection protection(Iterable<String> secretValues) {
        Set<String> candidates = new LinkedHashSet<>();
        Set<String> encoded = new LinkedHashSet<>();
        if (secretValues != null) {
            for (String value : secretValues)
                addCandidates(candidates, encoded, value);
        }
        if (candidates.isEmpty())
            return new Protection(null, null, List.of());

        List<String> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.comparingInt(String::length).reversed());
        Pattern folded = Pattern.compile(encoded.stream().sorted(Comparator.comparingInt(String::length).reversed()).map(SecretValueSanitizer::literalPattern).reduce((left, right) -> left + "|" + right).orElseThrow());
        return new Protection(Pattern.compile(ordered.stream().map(SecretValueSanitizer::literalPattern).reduce((left, right) -> left + "|" + right).orElseThrow()), folded, List.copyOf(encoded));
    }

    /** Uses regex syntax shared with JavaScript so Playwright can reuse the same filter for masks. */
    private static String literalPattern(String value) {
        return value.replaceAll("([\\\\.\\[\\]{}()*+?^$|])", "\\\\$1");
    }

    public static final class Protection {
        private final Pattern pattern;
        private final Pattern encoded;
        private final List<String> base64Values;

        private Protection(Pattern pattern, Pattern encoded, List<String> base64Values) {
            this.pattern = pattern;
            this.encoded = encoded;
            this.base64Values = base64Values;
        }

        public String sanitize(String text) {
            if (pattern == null || text == null)
                return text;

            String safe = SecretValueSanitizer.sanitize(text, pattern);
            // MIME allows arbitrary line folding. Match compact Base64 while preserving a map
            // to the original span, so the entire folded representation is removed together.
            StringBuilder compact = new StringBuilder();
            int[] positions = new int[safe.length()];
            int[] ends = new int[safe.length()];
            for (int index = 0; index < safe.length(); index++) {
                int start = index;
                char character = safe.charAt(index);
                if (character == '\\' && index + 1 < safe.length() && "nrt".indexOf(safe.charAt(index + 1)) >= 0) {
                    index++;
                    continue;
                }
                if (character == '%' && index + 2 < safe.length()) {
                    int high = Character.digit(safe.charAt(index + 1), 16);
                    int low = Character.digit(safe.charAt(index + 2), 16);
                    if (high >= 0 && low >= 0) {
                        character = (char) ((high << 4) | low);
                        index += 2;
                    }
                }
                if (character != ' ' && character != '\t' && character != '\r' && character != '\n') {
                    positions[compact.length()] = start;
                    ends[compact.length()] = index + 1;
                    compact.append(character);
                }
            }
            Matcher matcher = encoded.matcher(compact);
            StringBuilder result = new StringBuilder();
            int previous = 0;
            while (matcher.find()) {
                int start = positions[matcher.start()];
                int end = ends[matcher.end() - 1];
                result.append(safe, previous, start).append(REDACTED);
                previous = end;
            }
            return result.append(safe, previous, safe.length()).toString();
        }

        public JsonNode sanitize(JsonNode node) { return pattern == null || node == null ? node : SecretValueSanitizer.sanitize(node, this); }
        public Pattern maskPattern() { return pattern; }
        public List<String> base64Values() { return base64Values; }
    }

    private static void addCandidates(Set<String> candidates, Set<String> encoded, String value) {
        if (value == null || value.isEmpty())
            return;

        addTextCandidate(candidates, encoded, value);
        try {
            collectTextValues(JSON.readTree(value), candidates, encoded);
        } catch (RuntimeException ignored) {
            // Plain-text secrets are already represented by their complete value.
        }
    }

    private static void collectTextValues(JsonNode node, Set<String> candidates, Set<String> encoded) {
        if (node == null)
            return;

        if (node.isString()) {
            if (!node.stringValue().isEmpty())
                addTextCandidate(candidates, encoded, node.stringValue());

            return;
        }
        if (node.isArray()) {
            for (int index = 0; index < node.size(); index++)
                collectTextValues(node.get(index), candidates, encoded);

            return;
        }
        if (node.isObject())
            node.properties().forEach(entry -> collectTextValues(entry.getValue(), candidates, encoded));
    }

    private static void addTextCandidate(Set<String> candidates, Set<String> encoded, String value) {
        addRepresentations(candidates, encoded, value);
        Matcher header = AUTHORIZATION.matcher(value);
        if (header.matches())
            addRepresentations(candidates, encoded, header.group(1));
    }

    /** One encoding layer only: recursively encoding generated candidates would grow without bound. */
    private static void addRepresentations(Set<String> candidates, Set<String> encoded, String value) {
        addEscaped(candidates, value);
        String normalized = value.replaceAll("[\\s\\u00a0]+", " ").strip();
        if (!normalized.isEmpty())
            addEscaped(candidates, normalized);

        for (var charset : List.of(StandardCharsets.UTF_8, StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE)) {
            byte[] bytes = value.getBytes(charset);
            addBase64(candidates, encoded, bytes);
            if (charset != StandardCharsets.UTF_8) {
                byte[] withBom = new byte[bytes.length + 2];
                withBom[0] = (byte) (charset == StandardCharsets.UTF_16LE ? 0xff : 0xfe);
                withBom[1] = (byte) (charset == StandardCharsets.UTF_16LE ? 0xfe : 0xff);
                System.arraycopy(bytes, 0, withBom, 2, bytes.length);
                addBase64(candidates, encoded, withBom);
            }
        }
    }

    private static void addBase64(Set<String> candidates, Set<String> encodedValues, byte[] bytes) {
        for (Base64.Encoder encoder : List.of(Base64.getEncoder(), Base64.getUrlEncoder())) {
            String encoded = encoder.encodeToString(bytes);
            for (String text : List.of(encoded, encoded.replaceFirst("=+$", ""))) {
                encodedValues.add(text);
                addEscaped(candidates, text);
                for (int width : List.of(64, 76)) {
                    String folded = String.join("\r\n", text.split("(?<=\\G.{" + width + "})"));
                    addEscaped(candidates, folded);
                    addEscaped(candidates, folded.replace("\r\n", "\n"));
                    addEscaped(candidates, folded.replace("\r\n", " "));
                }
            }
        }
    }

    private static void addEscaped(Set<String> candidates, String text) {
        candidates.add(text);
        String quoted = JSON.writeValueAsString(text);
        candidates.add(quoted.substring(1, quoted.length() - 1));
        candidates.add(text.replace("'", "''"));
        String encoded = URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
        candidates.add(encoded);
        // Percent escapes are case-insensitive, while the protected text itself is not.
        candidates.add(Pattern.compile("%[0-9A-F]{2}").matcher(encoded).replaceAll(match -> match.group().toLowerCase(java.util.Locale.ROOT)));
    }
}
