package app.alertify.worker.contract;

import java.util.Objects;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Structured value of a {@code DB_SECRET}. The backend validates and
 * serialises it into the canonical JSON that gets encrypted, and workers
 * parse that same JSON back when a template declares a parameter of this
 * type. The password is never included in {@link #toString()}.
 */
public record DatabaseCredentials(
    DatabaseEngine engine,
    String host,
    int port,
    String database,
    String username,
    String password,
    String options
) {

    public static final int MIN_PORT = 1;
    public static final int MAX_PORT = 65_535;
    private static final String JSON_FIELD_DATABASE = "database";
    private static final String JSON_FIELD_ENGINE = "engine";
    private static final String JSON_FIELD_HOST = "host";
    private static final String JSON_FIELD_OPTIONS = "options";
    private static final String JSON_FIELD_PASSWORD = "password";
    private static final String JSON_FIELD_PORT = "port";
    private static final String JSON_FIELD_USERNAME = "username";
    private static final Set<String> JSON_FIELDS = Set.of(
            JSON_FIELD_ENGINE, JSON_FIELD_HOST, JSON_FIELD_PORT, JSON_FIELD_DATABASE,
            JSON_FIELD_USERNAME, JSON_FIELD_PASSWORD, JSON_FIELD_OPTIONS
    );
    private static final String JDBC_URL_PREFIX = "jdbc:";

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    public DatabaseCredentials {
        Objects.requireNonNull(engine, "engine must not be null");
        host = requireText(host, JSON_FIELD_HOST);
        if (port < MIN_PORT || port > MAX_PORT)
            throw new IllegalArgumentException("port must be between " + MIN_PORT + " and " + MAX_PORT);

        database = requireText(database, JSON_FIELD_DATABASE);
        username = requireText(username, JSON_FIELD_USERNAME);
        if (password == null || password.isEmpty())
            throw new IllegalArgumentException("password must not be empty");

        options = normalizeOptional(options);
        if (engine == DatabaseEngine.OTHER && (options == null || !options.startsWith(JDBC_URL_PREFIX)))
            throw new IllegalArgumentException("options must contain the full JDBC URL (jdbc:...) when engine is OTHER");
    }

    /**
     * Parses the canonical JSON representation. Unknown keys, missing keys and
     * values of the wrong JSON type are rejected with
     * {@link IllegalArgumentException}.
     */
    public static DatabaseCredentials fromJson(String json) {
        if (json == null || json.isBlank())
            throw new IllegalArgumentException("Database credentials JSON must not be blank");

        JsonNode node;
        try {
            node = JSON_MAPPER.readTree(json);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Database credentials must be valid JSON", exception);
        }
        return fromJson(node);
    }

    public static DatabaseCredentials fromJson(JsonNode node) {
        if (node == null || !node.isObject())
            throw new IllegalArgumentException("Database credentials must be a JSON object");

        for (String key : node.propertyNames()) {
            if (!JSON_FIELDS.contains(key))
                throw new IllegalArgumentException("Unknown database credentials field '" + key + "'");
        }

        return new DatabaseCredentials(
                engine(node.get(JSON_FIELD_ENGINE)),
                text(node.get(JSON_FIELD_HOST), JSON_FIELD_HOST),
                port(node.get(JSON_FIELD_PORT)),
                text(node.get(JSON_FIELD_DATABASE), JSON_FIELD_DATABASE),
                text(node.get(JSON_FIELD_USERNAME), JSON_FIELD_USERNAME),
                text(node.get(JSON_FIELD_PASSWORD), JSON_FIELD_PASSWORD),
                optionalText(node.get(JSON_FIELD_OPTIONS))
        );
    }

    /** Canonical JSON with a fixed key order; {@code options} is {@code null} when absent. */
    public String toJson() {
        return JSON_MAPPER.writeValueAsString(toJsonNode());
    }

    public ObjectNode toJsonNode() {
        ObjectNode node = JSON_MAPPER.createObjectNode();
        node.put(JSON_FIELD_ENGINE, engine.name());
        node.put(JSON_FIELD_HOST, host);
        node.put(JSON_FIELD_PORT, port);
        node.put(JSON_FIELD_DATABASE, database);
        node.put(JSON_FIELD_USERNAME, username);
        node.put(JSON_FIELD_PASSWORD, password);
        if (options == null)
            node.putNull(JSON_FIELD_OPTIONS);
        else
            node.put(JSON_FIELD_OPTIONS, options);
        return node;
    }

    @Override
    public String toString() {
        return "DatabaseCredentials[engine=" + engine + ", host=" + host + ", port=" + port
                + ", database=" + database + ", username=" + username + ", password=****"
                + ", options=" + options + "]";
    }

    private static DatabaseEngine engine(JsonNode node) {
        if (node == null || !node.isString())
            throw new IllegalArgumentException("engine is required");

        try {
            return DatabaseEngine.valueOf(node.stringValue().trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unsupported database engine '" + node.stringValue() + "'", exception);
        }
    }

    private static int port(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt())
            throw new IllegalArgumentException("port must be an integer between " + MIN_PORT + " and " + MAX_PORT);

        return node.intValue();
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
            throw new IllegalArgumentException("options must be a string");

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
