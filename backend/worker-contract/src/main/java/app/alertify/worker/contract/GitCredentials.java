package app.alertify.worker.contract;

import java.util.Objects;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Structured value of a {@code GIT_SECRET}. The backend validates and
 * serialises it into the canonical JSON that gets encrypted, and workers
 * parse that same JSON back when a template declares a parameter of this
 * type. The token is never included in {@link #toString()}.
 */
public record GitCredentials(
    GitProvider provider,
    String host,
    String username,
    String token,
    String tokenExpiresAt
) {

    private static final String JSON_FIELD_PROVIDER = "provider";
    private static final String JSON_FIELD_HOST = "host";
    private static final String JSON_FIELD_USERNAME = "username";
    private static final String JSON_FIELD_TOKEN = "token";
    private static final String JSON_FIELD_TOKEN_EXPIRES_AT = "tokenExpiresAt";
    private static final Set<String> JSON_FIELDS = Set.of(
            JSON_FIELD_PROVIDER, JSON_FIELD_HOST, JSON_FIELD_USERNAME, JSON_FIELD_TOKEN, JSON_FIELD_TOKEN_EXPIRES_AT
    );

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    public GitCredentials {
        Objects.requireNonNull(provider, "provider must not be null");
        host = requireText(host, JSON_FIELD_HOST);
        username = normalizeOptional(username);
        if (token == null || token.isEmpty())
            throw new IllegalArgumentException("token must not be empty");

        tokenExpiresAt = normalizeOptional(tokenExpiresAt);
    }

    /**
     * Parses the canonical JSON representation. Unknown keys, missing keys and
     * values of the wrong JSON type are rejected with
     * {@link IllegalArgumentException}.
     */
    public static GitCredentials fromJson(String json) {
        if (json == null || json.isBlank())
            throw new IllegalArgumentException("Git credentials JSON must not be blank");

        JsonNode node;
        try {
            node = JSON_MAPPER.readTree(json);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Git credentials must be valid JSON", exception);
        }
        return fromJson(node);
    }

    public static GitCredentials fromJson(JsonNode node) {
        if (node == null || !node.isObject())
            throw new IllegalArgumentException("Git credentials must be a JSON object");

        for (String key : node.propertyNames()) {
            if (!JSON_FIELDS.contains(key))
                throw new IllegalArgumentException("Unknown git credentials field '" + key + "'");
        }

        return new GitCredentials(
                provider(node.get(JSON_FIELD_PROVIDER)),
                text(node.get(JSON_FIELD_HOST), JSON_FIELD_HOST),
                optionalText(node.get(JSON_FIELD_USERNAME)),
                text(node.get(JSON_FIELD_TOKEN), JSON_FIELD_TOKEN),
                optionalText(node.get(JSON_FIELD_TOKEN_EXPIRES_AT))
        );
    }

    /** Canonical JSON with a fixed key order; optional fields are {@code null} when absent. */
    public String toJson() {
        return JSON_MAPPER.writeValueAsString(toJsonNode());
    }

    public ObjectNode toJsonNode() {
        ObjectNode node = JSON_MAPPER.createObjectNode();
        node.put(JSON_FIELD_PROVIDER, provider.name());
        node.put(JSON_FIELD_HOST, host);
        if (username == null)
            node.putNull(JSON_FIELD_USERNAME);
        else
            node.put(JSON_FIELD_USERNAME, username);
        node.put(JSON_FIELD_TOKEN, token);
        if (tokenExpiresAt == null)
            node.putNull(JSON_FIELD_TOKEN_EXPIRES_AT);
        else
            node.put(JSON_FIELD_TOKEN_EXPIRES_AT, tokenExpiresAt);
        return node;
    }

    @Override
    public String toString() {
        return "GitCredentials[provider=" + provider + ", host=" + host + ", username=" + username
                + ", token=****, tokenExpiresAt=" + tokenExpiresAt + "]";
    }

    private static GitProvider provider(JsonNode node) {
        if (node == null || !node.isString())
            throw new IllegalArgumentException("provider is required");

        try {
            return GitProvider.valueOf(node.stringValue().trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unsupported git provider '" + node.stringValue() + "'", exception);
        }
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
            throw new IllegalArgumentException("value must be a string");

        return node.stringValue();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(field + " must not be blank");

        return value.trim();
    }

    private static String normalizeOptional(String value) {
        if (value == null)
            return null;

        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
