package app.alertify.playwright;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Parsing and conversion shared by browser execution and backend value preparation. */
public final class PlaywrightDslValues {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern TOKEN = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"|\\S+");

    private PlaywrightDslValues() { }

    public static List<String> tokens(String value) {
        List<String> result = new ArrayList<>();
        var matcher = TOKEN.matcher(value);
        int end = 0;
        while (matcher.find()) {
            if (!value.substring(end, matcher.start()).isBlank())
                throw new IllegalArgumentException("Invalid command syntax");

            result.add(matcher.group());
            end = matcher.end();
            if (end < value.length() && !Character.isWhitespace(value.charAt(end)))
                throw new IllegalArgumentException("Command arguments must be separated by whitespace");
        }
        return List.copyOf(result);
    }

    public static String quoted(String token) {
        JsonNode value;
        try { value = JSON.readTree(token); }
        catch (RuntimeException failure) { throw new IllegalArgumentException("A valid JSON string in double quotes is required"); }
        if (value == null || !value.isString())
            throw new IllegalArgumentException("A string in double quotes is required");

        return value.stringValue();
    }

    public static String pointer(String value) {
        if (value.length() > 2000 || (!value.isEmpty() && !value.startsWith("/")))
            throw new IllegalArgumentException("Invalid JSON Pointer");

        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == '~' && (++index >= value.length() || (value.charAt(index) != '0' && value.charAt(index) != '1')))
                throw new IllegalArgumentException("Invalid JSON Pointer escape");
        }
        JsonPointer.compile(value);
        return value;
    }

    public static String key(String name, String pointer) {
        return pointer == null ? name : name + "\u0000" + HexFormat.of().formatHex(pointer.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static String select(JsonNode result, String pointer) {
        JsonNode selected = result.at(pointer(pointer));
        if (selected.isMissingNode())
            throw new IllegalArgumentException("JSON Pointer does not resolve a value");

        return selected.isString() ? selected.stringValue() : selected.toString();
    }
}
