package app.alertify.worker.contract;

import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Generic username/password credentials stored by a {@code USERNAME_PASSWORD}
 * secret. The username is optional and the password is never included in
 * {@link #toString()}.
 */
public record UsernamePasswordCredentials(String username, String password) {

    private static final String JSON_FIELD_USERNAME = "username";
    private static final String JSON_FIELD_PASSWORD = "password";
    private static final Set<String> JSON_FIELDS = Set.of(JSON_FIELD_USERNAME, JSON_FIELD_PASSWORD);
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    public UsernamePasswordCredentials {
        username = normalizeOptional(username);
        if (password == null || password.isEmpty())
            throw new IllegalArgumentException("password must not be empty");
    }

    public static UsernamePasswordCredentials fromJson(String json) {
        if (json == null || json.isBlank())
            throw new IllegalArgumentException("Username/password credentials JSON must not be blank");

        JsonNode node;
        try {
            node = JSON_MAPPER.readTree(json);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Username/password credentials must be valid JSON", exception);
        }
        return fromJson(node);
    }

    public static UsernamePasswordCredentials fromJson(JsonNode node) {
        if (node == null || !node.isObject())
            throw new IllegalArgumentException("Username/password credentials must be a JSON object");

        for (String key : node.propertyNames()) {
            if (!JSON_FIELDS.contains(key))
                throw new IllegalArgumentException("Unknown username/password credentials field '" + key + "'");
        }
        return new UsernamePasswordCredentials(optionalText(node.get(JSON_FIELD_USERNAME)), text(node.get(JSON_FIELD_PASSWORD), JSON_FIELD_PASSWORD));
    }

    public String toJson() {
        return JSON_MAPPER.writeValueAsString(toJsonNode());
    }

    public ObjectNode toJsonNode() {
        ObjectNode node = JSON_MAPPER.createObjectNode();
        if (username == null)
            node.putNull(JSON_FIELD_USERNAME);
        else
            node.put(JSON_FIELD_USERNAME, username);
        node.put(JSON_FIELD_PASSWORD, password);
        return node;
    }

    @Override
    public String toString() {
        return "UsernamePasswordCredentials[username=" + username + ", password=****]";
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.isString())
            throw new IllegalArgumentException(field + " is required");

        return node.stringValue();
    }

    private static String optionalText(JsonNode node) {
        if (node == null || node.isNull())
            return null;

        if (!node.isString())
            throw new IllegalArgumentException("username must be a string or null");

        return node.stringValue();
    }

    private static String normalizeOptional(String value) {
        if (value == null)
            return null;

        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
