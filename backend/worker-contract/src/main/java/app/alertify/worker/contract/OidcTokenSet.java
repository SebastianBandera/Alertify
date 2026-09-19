package app.alertify.worker.contract;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Structured value of an {@code OIDC_TOKEN_SET} secret. Tokens are treated as
 * opaque values: an ID token is a JWT, but access and refresh tokens are not
 * required to be JWTs. No token value is ever included in {@link #toString()}.
 */
public record OidcTokenSet(
    String accessToken,
    String refreshToken,
    String idToken,
    String tokenType,
    Instant expiresAt,
    Instant refreshExpiresAt
) {

    private static final String JSON_FIELD_ACCESS_TOKEN = "accessToken";
    private static final String JSON_FIELD_REFRESH_TOKEN = "refreshToken";
    private static final String JSON_FIELD_ID_TOKEN = "idToken";
    private static final String JSON_FIELD_TOKEN_TYPE = "tokenType";
    private static final String JSON_FIELD_EXPIRES_AT = "expiresAt";
    private static final String JSON_FIELD_REFRESH_EXPIRES_AT = "refreshExpiresAt";
    private static final Set<String> JSON_FIELDS = Set.of(
            JSON_FIELD_ACCESS_TOKEN, JSON_FIELD_REFRESH_TOKEN, JSON_FIELD_ID_TOKEN,
            JSON_FIELD_TOKEN_TYPE, JSON_FIELD_EXPIRES_AT, JSON_FIELD_REFRESH_EXPIRES_AT
    );

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    public OidcTokenSet {
        accessToken = requireToken(accessToken, JSON_FIELD_ACCESS_TOKEN);
        refreshToken = normalizeOptionalToken(refreshToken);
        idToken = normalizeOptionalToken(idToken);
        tokenType = requireText(tokenType, JSON_FIELD_TOKEN_TYPE);
    }

    /** Parses and strictly validates the canonical JSON representation. */
    public static OidcTokenSet fromJson(String json) {
        if (json == null || json.isBlank())
            throw new IllegalArgumentException("OIDC token set JSON must not be blank");

        JsonNode node;
        try {
            node = JSON_MAPPER.readTree(json);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("OIDC token set must be valid JSON", exception);
        }
        return fromJson(node);
    }

    public static OidcTokenSet fromJson(JsonNode node) {
        if (node == null || !node.isObject())
            throw new IllegalArgumentException("OIDC token set must be a JSON object");

        for (String key : node.propertyNames()) {
            if (!JSON_FIELDS.contains(key))
                throw new IllegalArgumentException("Unknown OIDC token set field '" + key + "'");
        }

        return new OidcTokenSet(
                text(node.get(JSON_FIELD_ACCESS_TOKEN), JSON_FIELD_ACCESS_TOKEN),
                optionalText(node.get(JSON_FIELD_REFRESH_TOKEN), JSON_FIELD_REFRESH_TOKEN),
                optionalText(node.get(JSON_FIELD_ID_TOKEN), JSON_FIELD_ID_TOKEN),
                text(node.get(JSON_FIELD_TOKEN_TYPE), JSON_FIELD_TOKEN_TYPE),
                optionalInstant(node.get(JSON_FIELD_EXPIRES_AT), JSON_FIELD_EXPIRES_AT),
                optionalInstant(node.get(JSON_FIELD_REFRESH_EXPIRES_AT), JSON_FIELD_REFRESH_EXPIRES_AT)
        );
    }

    /** Canonical JSON with a fixed key order; optional values are {@code null} when absent. */
    public String toJson() {
        return JSON_MAPPER.writeValueAsString(toJsonNode());
    }

    public ObjectNode toJsonNode() {
        ObjectNode node = JSON_MAPPER.createObjectNode();
        node.put(JSON_FIELD_ACCESS_TOKEN, accessToken);
        putOptional(node, JSON_FIELD_REFRESH_TOKEN, refreshToken);
        putOptional(node, JSON_FIELD_ID_TOKEN, idToken);
        node.put(JSON_FIELD_TOKEN_TYPE, tokenType);
        putOptional(node, JSON_FIELD_EXPIRES_AT, expiresAt == null ? null : expiresAt.toString());
        putOptional(node, JSON_FIELD_REFRESH_EXPIRES_AT, refreshExpiresAt == null ? null : refreshExpiresAt.toString());
        return node;
    }

    @Override
    public String toString() {
        return "OidcTokenSet[accessToken=****, refreshToken=" + redacted(refreshToken)
                + ", idToken=" + redacted(idToken) + ", tokenType=" + tokenType
                + ", expiresAt=" + expiresAt + ", refreshExpiresAt=" + refreshExpiresAt + "]";
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.isString())
            throw new IllegalArgumentException(field + " is required");

        return node.stringValue();
    }

    private static String optionalText(JsonNode node, String field) {
        if (node == null || node.isNull())
            return null;

        if (!node.isString())
            throw new IllegalArgumentException(field + " must be a string");

        return node.stringValue();
    }

    private static Instant optionalInstant(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null || value.isBlank())
            return null;

        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(field + " must be an RFC 3339 instant", exception);
        }
    }

    private static String requireToken(String value, String field) {
        if (value == null || value.isEmpty())
            throw new IllegalArgumentException(field + " must not be empty");

        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(field + " must not be blank");

        return value.trim();
    }

    private static String normalizeOptionalToken(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static void putOptional(ObjectNode node, String field, String value) {
        if (value == null)
            node.putNull(field);
        else
            node.put(field, value);
    }

    private static String redacted(String value) {
        return value == null ? "null" : "****";
    }
}
