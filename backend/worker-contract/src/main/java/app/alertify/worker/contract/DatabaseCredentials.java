package app.alertify.worker.contract;

import java.util.Objects;

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
    private static final String JDBC_URL_PREFIX = "jdbc:";

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    public DatabaseCredentials {
        Objects.requireNonNull(engine, "engine must not be null");
        host = requireText(host, "host");
        if (port < MIN_PORT || port > MAX_PORT)
            throw new IllegalArgumentException("port must be between " + MIN_PORT + " and " + MAX_PORT);

        database = requireText(database, "database");
        username = requireText(username, "username");
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
            switch (key) {
                case "engine", "host", "port", "database", "username", "password", "options" -> { }
                default -> throw new IllegalArgumentException("Unknown database credentials field '" + key + "'");
            }
        }

        return new DatabaseCredentials(
                engine(node.get("engine")),
                text(node.get("host"), "host"),
                port(node.get("port")),
                text(node.get("database"), "database"),
                text(node.get("username"), "username"),
                text(node.get("password"), "password"),
                optionalText(node.get("options"))
        );
    }

    /** Canonical JSON with a fixed key order; {@code options} is {@code null} when absent. */
    public String toJson() {
        return JSON_MAPPER.writeValueAsString(toJsonNode());
    }

    public ObjectNode toJsonNode() {
        ObjectNode node = JSON_MAPPER.createObjectNode();
        node.put("engine", engine.name());
        node.put("host", host);
        node.put("port", port);
        node.put("database", database);
        node.put("username", username);
        node.put("password", password);
        if (options == null)
            node.putNull("options");
        else
            node.put("options", options);
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
