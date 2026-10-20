package app.alertify.playwright;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.alertify.worker.contract.SecretValueSanitizer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Checks data literals, leaving DSL references, numeric operands and identity metadata intact. */
final class PlaywrightDraftProtection {
    static final String CODE = "PLAYWRIGHT_DRAFT_SENSITIVE_CONTENT";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern QUOTED = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'");
    private final SecretValueSanitizer.Protection values;
    private final SecretValueSanitizer.Protection identifiers;

    PlaywrightDraftProtection(List<String> sensitive) {
        values = SecretValueSanitizer.protection(sensitive);
        List<String> longer = new ArrayList<>();
        for (String value : sensitive) collectIdentifiers(value, longer);

        identifiers = SecretValueSanitizer.protection(longer);
    }

    void validate(JsonNode document) {
        validateUrl(document.path("settings").path("url").asString(""), "URL");
        validateSteps(document.path("settings").path("steps").asString(""), "script");
        validateSteps(document.path("proposal").asString(""), "proposal");
    }

    void validateSteps(String steps, String field) {
        String[] lines = steps.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index].strip();
            String location = field + " line " + (index + 1);
            if (line.isEmpty())
                continue;

            if (line.startsWith("#")) {
                check(line, location);
                continue;
            }
            // Alias names, like JSON pointers and reference names, are declared metadata.
            if (line.matches("(?i)FILL\\s+vars\\.[A-Za-z_][A-Za-z0-9_]{0,63}"))
                continue;

            PlaywrightPageEngine.Command command;
            try {
                // Parse each line with a current element so unfinished drafts can still be saved.
                command = PlaywrightPageEngine.parseCommands("QUERY #draft-validation\n" + line).getLast();
            } catch (IllegalArgumentException failure) {
                check(line, location);
                continue;
            }
            switch (command.type()) {
                case QUERY, WAIT_VISIBLE, WAIT_HIDDEN -> validateSelector(command.first(), location);
                case CHECK_TEXT, SELECT -> check(command.first(), location);
                case CHECK_ATTRIBUTE -> {
                    check(command.second(), location);
                    checkIdentifier(command.first(), location);
                }
                case CHECK_URL -> validateUrl(command.first(), location);
                default -> { /* No page-derived literal: references, aliases, durations or numeric checks. */ }
            }
        }
    }

    private void validateSelector(String selector, String location) {
        Matcher matcher = QUOTED.matcher(selector);
        while (matcher.find()) {
            String token = matcher.group();
            String literal;
            try { literal = token.startsWith("\"") ? JSON.readTree(token).asString() : decodeCss(token.substring(1, token.length() - 1)); }
            catch (RuntimeException failure) { literal = decodeCss(token.substring(1, token.length() - 1)); }
            check(literal, location);
        }
        String unquoted = QUOTED.matcher(selector).replaceAll("");
        if (unquoted.indexOf('"') >= 0 || unquoted.indexOf('\'') >= 0)
            check(selector, location);

        int textEngine = selector.indexOf("text=");
        if (textEngine >= 0)
            check(decodeCss(selector.substring(textEngine + 5)), location);

        // Short results such as "1" must not turn #field1 into a different selector.
        // Longer credentials embedded in CSS identifiers are still sensitive literals.
        String decoded = decodeCss(selector);
        checkIdentifier(decoded, location);
        checkExactIdentifier(decoded, location);
        Matcher names = Pattern.compile("[#.]([\\p{L}\\p{N}_-]+)").matcher(decoded);
        while (names.find()) checkExactIdentifier(names.group(1), location);

        Matcher attributes = Pattern.compile("\\[[^\\]=]+\\s*=\\s*([^\\]\"'\\s]+)").matcher(selector);
        while (attributes.find()) check(decodeCss(attributes.group(1)), location);
    }

    private void validateUrl(String url, String location) {
        checkIdentifier(url, location);
        try {
            URI uri = URI.create(url);
            if (!uri.isAbsolute() || uri.getHost() == null) {
                check(url, location);
                return;
            }
            for (String component : List.of(uri.getRawPath() == null ? "" : uri.getRawPath(), uri.getRawQuery() == null ? "" : uri.getRawQuery(), uri.getRawFragment() == null ? "" : uri.getRawFragment(), uri.getRawUserInfo() == null ? "" : uri.getRawUserInfo()))
                check(component, location);

        } catch (IllegalArgumentException failure) {
            if (failure instanceof SensitiveContentFailure) throw failure;

            check(url, location);
        }
    }

    private void check(String text, String location) {
        if (text != null && !text.equals(values.sanitize(text)))
            throw new SensitiveContentFailure(location);
    }

    private void checkIdentifier(String text, String location) {
        if (text != null && !text.equals(identifiers.sanitize(text)))
            throw new SensitiveContentFailure(location);
    }

    private void checkExactIdentifier(String text, String location) {
        if (SecretValueSanitizer.REDACTED.equals(values.sanitize(text)))
            throw new SensitiveContentFailure(location);
    }

    /** Decode CSS escapes locally; never include an original selector in a diagnostic. */
    private static String decodeCss(String text) {
        Matcher matcher = Pattern.compile("\\\\([0-9a-fA-F]{1,6})[ \\t\\r\\n\\f]?|\\\\(.)", Pattern.DOTALL).matcher(text);
        return matcher.replaceAll(match -> {
            if (match.group(1) == null) return Matcher.quoteReplacement(match.group(2));

            int codePoint = Integer.parseInt(match.group(1), 16);
            return Matcher.quoteReplacement(Character.isValidCodePoint(codePoint) ? new String(Character.toChars(codePoint)) : "\ufffd");
        });
    }

    private static void collectIdentifiers(String value, List<String> target) {
        if (value == null || value.isEmpty())
            return;

        try {
            JsonNode node = JSON.readTree(value);
            if (node.isContainer()) {
                node.forEach(child -> collectIdentifiers(child.isString() ? child.asString() : child.toString(), target));
                return;
            }
        } catch (RuntimeException ignored) { /* Plain-text value. */ }
        if (value.length() >= 4)
            target.add(value);
    }

    static final class SensitiveContentFailure extends IllegalArgumentException {
        SensitiveContentFailure(String location) {
            super(CODE + ": protected literal in " + location + ". Remove it and use an existing configuration/secret reference. The script was not rewritten.");
        }
    }
}
