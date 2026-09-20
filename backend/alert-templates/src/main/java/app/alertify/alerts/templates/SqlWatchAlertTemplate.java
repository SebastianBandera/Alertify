package app.alertify.alerts.templates;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import app.alertify.alerts.AlertEvaluator;
import app.alertify.alerts.AlertExecutionContext;
import app.alertify.alerts.AlertResult;
import app.alertify.alerts.template.annotation.AlertParameter;
import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.alerts.template.annotation.AlertTemplate;
import app.alertify.alerts.template.annotation.AlertTemplateTag;
import app.alertify.worker.contract.BinaryPayloadCodec;
import app.alertify.worker.contract.DatabaseConnections;
import app.alertify.worker.contract.DatabaseCredentials;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Compares the current result of a prepared SQL query with a rolling SQLite
 * snapshot stored in a writable binary configuration or secret.
 */
@AlertTemplate(
    nameKey = "alerts.template.sqlWatch.name",
    descriptionKey = "alerts.template.sqlWatch.description",
    tags = @AlertTemplateTag(nameKey = "alerts.templateTag.database", color = "#F59E0B"),
    sourcePath = "app/alertify/alerts/templates/SqlWatchAlertTemplate.java"
)
public final class SqlWatchAlertTemplate implements AlertEvaluator {

    private static final int FORMAT_VERSION = 1;
    private static final int MAX_REPORTED_ROWS = 100;
    private static final int MAX_TEXT_CODE_POINTS = 4_096;
    private static final int MAX_TIMEOUT_SECONDS = 604_800;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @AlertParameter(
        labelKey = "alerts.template.sqlWatch.credentials",
        descriptionKey = "alerts.template.sqlWatch.credentialsDescription",
        order = 1,
        allowedSources = { AlertParameterSource.SECRET },
        allowedSecretValueTypes = "DB_SECRET"
    )
    private final DatabaseCredentials credentials;

    @AlertParameter(
        labelKey = "alerts.template.sqlWatch.sql",
        descriptionKey = "alerts.template.sqlWatch.sqlDescription",
        multiline = true,
        order = 2
    )
    private final String sql;

    @AlertParameter(
        labelKey = "alerts.template.sqlWatch.sqlParametersJson",
        descriptionKey = "alerts.template.sqlWatch.sqlParametersJsonDescription",
        defaultValue = "[]",
        multiline = true,
        required = false,
        order = 3
    )
    private final String sqlParametersJson;

    @AlertParameter(
        labelKey = "alerts.template.sqlWatch.keyColumnsJson",
        descriptionKey = "alerts.template.sqlWatch.keyColumnsJsonDescription",
        multiline = true,
        order = 4
    )
    private final String keyColumnsJson;

    @AlertParameter(
        labelKey = "alerts.template.sqlWatch.snapshot",
        descriptionKey = "alerts.template.sqlWatch.snapshotDescription",
        order = 5,
        allowedSources = { AlertParameterSource.CONFIGURATION, AlertParameterSource.SECRET },
        allowedConfigurationValueTypes = "BINARY",
        allowedSecretValueTypes = "BINARY",
        writableBindingRequired = true
    )
    private byte[] snapshot;

    @AlertParameter(
        labelKey = "alerts.template.sqlWatch.maxReportedRows",
        descriptionKey = "alerts.template.sqlWatch.maxReportedRowsDescription",
        options = { "0", "5", "10", "20", "50", "100" },
        defaultValue = "20",
        order = 6
    )
    private final int maxReportedRows;

    @AlertParameter(
        labelKey = "alerts.template.sqlWatch.alertDescription",
        descriptionKey = "alerts.template.sqlWatch.alertDescriptionDescription",
        required = false,
        order = 7,
        allowedSources = { AlertParameterSource.TEXT, AlertParameterSource.CONFIGURATION }
    )
    private final String description;

    @AlertParameter(
        labelKey = "alerts.template.sqlWatch.timeout",
        descriptionKey = "alerts.template.sqlWatch.timeoutDescription",
        options = { "1", "3", "5", "10", "30", "300", "3600", "86400", "604800" },
        defaultValue = "10",
        order = 8
    )
    private final int timeoutSeconds;

    private final JsonNode sqlParameters;
    private final List<String> keyColumns;

    public SqlWatchAlertTemplate(DatabaseCredentials credentials, String sql, String sqlParametersJson, String keyColumnsJson, byte[] snapshot, int maxReportedRows, String description, int timeoutSeconds) {
        if (credentials == null)
            throw new IllegalArgumentException("credentials must not be null");

        this.credentials = credentials;
        this.sql = requireText(sql, "sql");
        this.sqlParametersJson = sqlParametersJson == null || sqlParametersJson.isBlank() ? "[]" : sqlParametersJson;
        this.keyColumnsJson = requireText(keyColumnsJson, "keyColumnsJson");
        this.sqlParameters = parseSqlParameters(this.sqlParametersJson);
        this.keyColumns = parseKeyColumns(this.keyColumnsJson);
        this.snapshot = snapshot == null ? new byte[0] : snapshot;
        if (this.snapshot.length > BinaryPayloadCodec.DEFAULT_MAX_VALUE_BYTES)
            throw new IllegalArgumentException("snapshot exceeds the maximum binary value size");

        if (maxReportedRows < 0 || maxReportedRows > MAX_REPORTED_ROWS)
            throw new IllegalArgumentException("maxReportedRows must be between 0 and " + MAX_REPORTED_ROWS);

        this.maxReportedRows = maxReportedRows;
        this.description = optionalText(description);
        if (timeoutSeconds <= 0 || timeoutSeconds > MAX_TIMEOUT_SECONDS)
            throw new IllegalArgumentException("timeoutSeconds must be between 1 and " + MAX_TIMEOUT_SECONDS);

        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public AlertResult evaluate(AlertExecutionContext context) {
        Path directory = null;
        long startedNanos = System.nanoTime();
        try {
            directory = Files.createTempDirectory("alertify-sql-watch-");
            Path currentPath = directory.resolve("current.sqlite");
            SnapshotMetadata current = createCurrentSnapshot(currentPath);
            Map<String, Object> status = baseStatus(current.rowCount(), elapsedMillis(startedNanos));
            if (snapshot.length == 0) {
                replaceSnapshot(currentPath);
                status.put("initialized", true);
                context.setState(state(current.rowCount(), 0, 0, 0, true));
                return AlertResult.success(status);
            }

            Path previousPath = directory.resolve("previous.sqlite");
            Files.write(previousPath, snapshot);
            SnapshotMetadata previous = readMetadata(previousPath);
            StructureDifference structure = structureDifference(previous, current);
            if (structure.changed()) {
                replaceSnapshot(currentPath);
                status.put("structuralChange", true);
                status.put("previousRows", previous.rowCount());
                status.put("addedColumns", structure.addedColumns());
                status.put("removedColumns", structure.removedColumns());
                status.put("changedColumns", structure.changedColumns());
                status.put("keyColumnsChanged", structure.keyColumnsChanged());
                context.setState(state(current.rowCount(), 0, 0, 0, true));
                return AlertResult.warn(status);
            }

            Difference difference = compare(previousPath, currentPath);
            status.put("addedRows", difference.added());
            status.put("removedRows", difference.removed());
            status.put("modifiedRows", difference.modified());
            if (difference.changed()) {
                replaceSnapshot(currentPath);
                status.put("samples", difference.samples());
                if (difference.truncatedValues() > 0)
                    status.put("truncatedSampleValues", difference.truncatedValues());

                context.setState(state(current.rowCount(), difference.added(), difference.removed(), difference.modified(), false));
                return AlertResult.warn(status);
            }

            context.setState(state(current.rowCount(), 0, 0, 0, false));
            return AlertResult.success(status);
        } catch (SQLException exception) {
            throw new IllegalStateException("SQL watch query failed (" + failureReason(exception) + ")");
        } catch (IOException exception) {
            throw new IllegalStateException("SQL watch could not manage its private snapshot file");
        } finally {
            deleteDirectory(directory);
        }
    }

    private SnapshotMetadata createCurrentSnapshot(Path path) throws SQLException {
        try (Connection snapshotConnection = sqlite(path);
                Connection sourceConnection = DatabaseConnections.open(credentials, Duration.ofSeconds(timeoutSeconds))) {
            initializeSnapshot(snapshotConnection);
            sourceConnection.setReadOnly(true);
            try (PreparedStatement query = sourceConnection.prepareStatement(sql)) {
                query.setQueryTimeout(timeoutSeconds);
                bindParameters(query, sqlParameters);
                try (ResultSet rows = query.executeQuery()) {
                    return populateSnapshot(snapshotConnection, rows);
                }
            }
        }
    }

    private SnapshotMetadata populateSnapshot(Connection connection, ResultSet rows) throws SQLException {
        List<Column> columns = columns(rows.getMetaData());
        Map<String, Column> byName = new HashMap<>();
        for (Column column : columns)
            byName.put(column.normalizedName(), column);

        List<Column> keys = new ArrayList<>();
        for (String key : keyColumns) {
            Column column = byName.get(normalize(key));
            if (column == null)
                throw new IllegalArgumentException("SQL watch query does not contain key column '" + key + "'");

            keys.add(column);
        }

        long rowCount = 0;
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO snapshot_rows(key_json, row_json) VALUES (?, ?)")) {
            while (rows.next()) {
                Map<Integer, JsonNode> values = new HashMap<>();
                for (Column column : columns)
                    values.put(column.index(), readValue(rows, column));

                ObjectNode row = JSON.createObjectNode();
                for (Column column : columns)
                    row.set(column.normalizedName(), values.get(column.index()));

                ArrayNode key = JSON.createArrayNode();
                for (Column column : keys) {
                    ObjectNode part = JSON.createObjectNode();
                    part.put("type", column.jdbcType());
                    part.set("value", values.get(column.index()));
                    key.add(part);
                }
                try {
                    insert.setString(1, JSON.writeValueAsString(key));
                    insert.setString(2, JSON.writeValueAsString(row));
                    insert.executeUpdate();
                } catch (SQLException exception) {
                    if (isConstraintViolation(exception))
                        throw new IllegalArgumentException("SQL watch key columns do not uniquely identify every row");

                    throw exception;
                }
                rowCount++;
            }
        }

        JsonNode schema = schema(columns);
        JsonNode normalizedKeys = normalizedKeys();
        try (PreparedStatement metadata = connection.prepareStatement("INSERT INTO snapshot_metadata(singleton_id, format_version, schema_json, key_columns_json, row_count) VALUES (1, ?, ?, ?, ?)")) {
            metadata.setInt(1, FORMAT_VERSION);
            metadata.setString(2, JSON.writeValueAsString(schema));
            metadata.setString(3, JSON.writeValueAsString(normalizedKeys));
            metadata.setLong(4, rowCount);
            metadata.executeUpdate();
        }
        connection.commit();
        return new SnapshotMetadata(schema, normalizedKeys, rowCount);
    }

    private static Connection sqlite(Path path) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
    }

    private static void initializeSnapshot(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=DELETE");
            statement.execute("CREATE TABLE snapshot_metadata(singleton_id INTEGER PRIMARY KEY CHECK(singleton_id = 1), format_version INTEGER NOT NULL, schema_json TEXT NOT NULL, key_columns_json TEXT NOT NULL, row_count INTEGER NOT NULL)");
            statement.execute("CREATE TABLE snapshot_rows(key_json TEXT PRIMARY KEY, row_json TEXT NOT NULL)");
        }
    }

    private static SnapshotMetadata readMetadata(Path path) {
        try (Connection connection = sqlite(path);
                Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("SELECT format_version, schema_json, key_columns_json, row_count FROM snapshot_metadata WHERE singleton_id = 1")) {
            if (!row.next() || row.getInt(1) != FORMAT_VERSION)
                throw invalidSnapshot();

            JsonNode schema = JSON.readTree(row.getString(2));
            JsonNode keys = JSON.readTree(row.getString(3));
            long rowCount = row.getLong(4);
            if (!schema.isArray() || !keys.isArray() || rowCount < 0 || row.next())
                throw invalidSnapshot();

            try (Statement countStatement = connection.createStatement();
                    ResultSet count = countStatement.executeQuery("SELECT COUNT(*) FROM snapshot_rows")) {
                if (!count.next() || count.getLong(1) != rowCount)
                    throw invalidSnapshot();
            }
            return new SnapshotMetadata(schema, keys, rowCount);
        } catch (SQLException | RuntimeException exception) {
            throw invalidSnapshot();
        }
    }

    private Difference compare(Path previousPath, Path currentPath) throws SQLException {
        try (Connection connection = sqlite(previousPath);
                PreparedStatement attach = connection.prepareStatement("ATTACH DATABASE ? AS current_snapshot")) {
            attach.setString(1, currentPath.toAbsolutePath().toString());
            attach.execute();
            long added = count(connection, "SELECT COUNT(*) FROM current_snapshot.snapshot_rows current LEFT JOIN snapshot_rows previous ON previous.key_json = current.key_json WHERE previous.key_json IS NULL");
            long removed = count(connection, "SELECT COUNT(*) FROM snapshot_rows previous LEFT JOIN current_snapshot.snapshot_rows current ON current.key_json = previous.key_json WHERE current.key_json IS NULL");
            long modified = count(connection, "SELECT COUNT(*) FROM current_snapshot.snapshot_rows current JOIN snapshot_rows previous ON previous.key_json = current.key_json WHERE previous.row_json <> current.row_json");
            int[] truncated = { 0 };
            Map<String, Object> samples = new LinkedHashMap<>();
            if (maxReportedRows > 0 && added > 0)
                samples.put("added", sampleRows(connection, "SELECT current.row_json FROM current_snapshot.snapshot_rows current LEFT JOIN snapshot_rows previous ON previous.key_json = current.key_json WHERE previous.key_json IS NULL ORDER BY current.key_json LIMIT ?", truncated));

            if (maxReportedRows > 0 && removed > 0)
                samples.put("removed", sampleRows(connection, "SELECT previous.row_json FROM snapshot_rows previous LEFT JOIN current_snapshot.snapshot_rows current ON current.key_json = previous.key_json WHERE current.key_json IS NULL ORDER BY previous.key_json LIMIT ?", truncated));

            if (maxReportedRows > 0 && modified > 0)
                samples.put("modified", sampleChanges(connection, truncated));

            return new Difference(added, removed, modified, samples, truncated[0]);
        }
    }

    private List<JsonNode> sampleRows(Connection connection, String sql, int[] truncated) throws SQLException {
        List<JsonNode> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, maxReportedRows);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next())
                    result.add(sanitizeSample(JSON.readTree(rows.getString(1)), truncated));
            }
        }
        return result;
    }

    private List<Map<String, JsonNode>> sampleChanges(Connection connection, int[] truncated) throws SQLException {
        List<Map<String, JsonNode>> result = new ArrayList<>();
        String sql = "SELECT previous.row_json, current.row_json FROM current_snapshot.snapshot_rows current JOIN snapshot_rows previous ON previous.key_json = current.key_json WHERE previous.row_json <> current.row_json ORDER BY current.key_json LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, maxReportedRows);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Map<String, JsonNode> change = new LinkedHashMap<>();
                    change.put("previous", sanitizeSample(JSON.readTree(rows.getString(1)), truncated));
                    change.put("current", sanitizeSample(JSON.readTree(rows.getString(2)), truncated));
                    result.add(change);
                }
            }
        }
        return result;
    }

    private static long count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(sql)) {
            return row.next() ? row.getLong(1) : 0;
        }
    }

    private static List<Column> columns(ResultSetMetaData metadata) throws SQLException {
        List<Column> columns = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            String name = requireText(metadata.getColumnLabel(index), "SQL result column label");
            String normalized = normalize(name);
            if (!names.add(normalized))
                throw new IllegalArgumentException("SQL watch query contains duplicate column label '" + name + "'");

            columns.add(new Column(name, normalized, metadata.getColumnType(index), metadata.getColumnTypeName(index), index));
        }
        columns.sort(Comparator.comparing(column -> column.normalizedName()));
        return columns;
    }

    private static JsonNode schema(List<Column> columns) {
        ArrayNode result = JSON.createArrayNode();
        for (Column column : columns) {
            ObjectNode item = JSON.createObjectNode();
            item.put("name", column.normalizedName());
            item.put("normalizedName", column.normalizedName());
            item.put("jdbcType", column.jdbcType());
            item.put("typeName", column.typeName() == null ? "" : column.typeName().toUpperCase(Locale.ROOT));
            result.add(item);
        }
        return result;
    }

    private JsonNode normalizedKeys() {
        ArrayNode result = JSON.createArrayNode();
        for (String key : keyColumns)
            result.add(normalize(key));

        return result;
    }

    private static JsonNode readValue(ResultSet rows, Column column) throws SQLException {
        int index = column.index();
        return switch (column.jdbcType()) {
            case Types.NULL -> JSON.nullNode();
            case Types.BOOLEAN, Types.BIT -> rows.getObject(index) == null ? JSON.nullNode() : JSON.valueToTree(rows.getBoolean(index));
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                    Types.DECIMAL, Types.NUMERIC, Types.REAL, Types.FLOAT, Types.DOUBLE -> numericValue(rows, index);
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> binaryValue(rows, index);
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
                    Types.CLOB, Types.NCLOB, Types.DATE, Types.TIME, Types.TIME_WITH_TIMEZONE,
                    Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE, Types.ROWID -> textValue(rows, index);
            default -> objectValue(rows, index, column.jdbcType());
        };
    }

    private static JsonNode numericValue(ResultSet rows, int index) throws SQLException {
        BigDecimal value = rows.getBigDecimal(index);
        if (value == null)
            return JSON.nullNode();

        return JSON.valueToTree(value.stripTrailingZeros());
    }

    private static JsonNode textValue(ResultSet rows, int index) throws SQLException {
        String value = rows.getString(index);
        return value == null ? JSON.nullNode() : JSON.valueToTree(value);
    }

    private static JsonNode objectValue(ResultSet rows, int index, int jdbcType) throws SQLException {
        Object value = rows.getObject(index);
        if (value == null)
            return JSON.nullNode();

        if (value instanceof CharSequence || value instanceof Character || value instanceof java.util.UUID)
            return JSON.valueToTree(value.toString());

        if (value instanceof Boolean booleanValue)
            return JSON.valueToTree(booleanValue);

        if (value instanceof Number number)
            return JSON.valueToTree(new BigDecimal(number.toString()).stripTrailingZeros());

        throw new IllegalArgumentException("SQL watch does not support JDBC type " + jdbcType);
    }

    private static JsonNode binaryValue(ResultSet rows, int index) throws SQLException {
        try (InputStream input = rows.getBinaryStream(index)) {
            if (input == null)
                return JSON.nullNode();

            MessageDigest digest = sha256Digest();
            long length = 0;
            byte[] buffer = new byte[8_192];
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read == 0)
                    continue;

                digest.update(buffer, 0, read);
                length += read;
            }
            ObjectNode result = JSON.createObjectNode();
            result.put("length", length);
            result.put("sha256", HexFormat.of().formatHex(digest.digest()));
            return result;
        } catch (IOException exception) {
            throw new SQLException("Could not read binary SQL value");
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private static StructureDifference structureDifference(SnapshotMetadata previous, SnapshotMetadata current) {
        boolean keyColumnsChanged = !previous.keyColumns().equals(current.keyColumns());
        Map<String, JsonNode> previousColumns = schemaByName(previous.schema());
        Map<String, JsonNode> currentColumns = schemaByName(current.schema());
        List<String> added = currentColumns.keySet().stream().filter(name -> !previousColumns.containsKey(name)).sorted().toList();
        List<String> removed = previousColumns.keySet().stream().filter(name -> !currentColumns.containsKey(name)).sorted().toList();
        List<String> changed = currentColumns.keySet().stream()
                .filter(previousColumns::containsKey)
                .filter(name -> !currentColumns.get(name).equals(previousColumns.get(name)))
                .sorted()
                .toList();
        return new StructureDifference(added, removed, changed, keyColumnsChanged);
    }

    private static Map<String, JsonNode> schemaByName(JsonNode schema) {
        Map<String, JsonNode> result = new HashMap<>();
        for (JsonNode column : schema) {
            JsonNode normalized = column.get("normalizedName");
            if (normalized == null || !normalized.isString() || result.put(normalized.stringValue(), column) != null)
                throw invalidSnapshot();
        }
        return result;
    }

    private static JsonNode sanitizeSample(JsonNode node, int[] truncated) {
        if (node.isString()) {
            String value = node.stringValue();
            int codePoints = value.codePointCount(0, value.length());
            if (codePoints <= MAX_TEXT_CODE_POINTS)
                return node;

            truncated[0]++;
            return JSON.valueToTree(value.substring(0, value.offsetByCodePoints(0, MAX_TEXT_CODE_POINTS)));
        }
        if (node.isArray()) {
            ArrayNode result = JSON.createArrayNode();
            for (JsonNode item : node)
                result.add(sanitizeSample(item, truncated));

            return result;
        }
        if (node.isObject()) {
            ObjectNode result = JSON.createObjectNode();
            node.properties().forEach(entry -> result.set(entry.getKey(), sanitizeSample(entry.getValue(), truncated)));
            return result;
        }
        return node;
    }

    private void replaceSnapshot(Path path) throws IOException {
        long size = Files.size(path);
        if (size > BinaryPayloadCodec.DEFAULT_MAX_VALUE_BYTES)
            throw new IllegalArgumentException("SQL watch snapshot exceeds the maximum binary value size");

        snapshot = Files.readAllBytes(path);
    }

    private Map<String, Object> baseStatus(long rows, long latencyMs) {
        Map<String, Object> status = new LinkedHashMap<>();
        if (description != null)
            status.put("description", description);

        status.put("rows", rows);
        status.put("timeoutSeconds", timeoutSeconds);
        status.put("latencyMs", latencyMs);
        status.put("checkedAt", Instant.now().toString());
        return status;
    }

    private static JsonNode parseSqlParameters(String configured) {
        JsonNode root = parseJson(configured, "sqlParametersJson");
        if (!root.isArray())
            throw new IllegalArgumentException("sqlParametersJson must be a JSON array");

        for (int index = 0; index < root.size(); index++) {
            JsonNode value = root.get(index);
            if (!(value.isNull() || value.isString() || value.isBoolean() || value.isNumber()))
                throw new IllegalArgumentException("sqlParametersJson item " + index + " must be a scalar JSON value or null");
        }
        return root;
    }

    private static List<String> parseKeyColumns(String configured) {
        JsonNode root = parseJson(configured, "keyColumnsJson");
        if (!root.isArray() || root.size() == 0)
            throw new IllegalArgumentException("keyColumnsJson must be a non-empty JSON array");

        List<String> result = new ArrayList<>();
        Set<String> normalized = new HashSet<>();
        for (int index = 0; index < root.size(); index++) {
            JsonNode value = root.get(index);
            if (!value.isString())
                throw new IllegalArgumentException("keyColumnsJson item " + index + " must be a column name");

            String key = requireText(value.stringValue(), "keyColumnsJson item " + index);
            if (!normalized.add(normalize(key)))
                throw new IllegalArgumentException("keyColumnsJson contains duplicate column '" + key + "'");

            result.add(key);
        }
        return List.copyOf(result);
    }

    private static JsonNode parseJson(String configured, String parameter) {
        try {
            JsonNode root = JSON.readTree(configured);
            if (root == null)
                throw new IllegalArgumentException(parameter + " must contain JSON");

            return root;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(parameter + " must contain valid JSON");
        }
    }

    private static void bindParameters(PreparedStatement statement, JsonNode parameters) throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            JsonNode value = parameters.get(index);
            int jdbcIndex = index + 1;
            if (value.isNull())
                statement.setNull(jdbcIndex, Types.NULL);
            else if (value.isString())
                statement.setString(jdbcIndex, value.stringValue());
            else if (value.isBoolean())
                statement.setBoolean(jdbcIndex, value.booleanValue());
            else if (value.isIntegralNumber())
                bindIntegral(statement, jdbcIndex, value.decimalValue());
            else
                statement.setBigDecimal(jdbcIndex, value.decimalValue());
        }
    }

    private static void bindIntegral(PreparedStatement statement, int index, BigDecimal value) throws SQLException {
        try {
            statement.setLong(index, value.longValueExact());
        } catch (ArithmeticException exception) {
            statement.setBigDecimal(index, value);
        }
    }

    private static boolean isConstraintViolation(SQLException exception) {
        return exception.getErrorCode() == 19 || "23000".equals(exception.getSQLState());
    }

    private static String failureReason(SQLException exception) {
        if (exception instanceof SQLTimeoutException)
            return "timeout";

        String state = exception.getSQLState();
        if (state != null && state.startsWith("28"))
            return "auth_failed";

        if (state != null && state.startsWith("08"))
            return "connection_failure";

        return "query_failure";
    }

    private static IllegalArgumentException invalidSnapshot() {
        return new IllegalArgumentException("SQL watch snapshot is invalid or corrupt");
    }

    private static String state(long rows, long added, long removed, long modified, boolean structuralChange) {
        return "rows=" + rows + ";added=" + added + ";removed=" + removed + ";modified=" + modified + ";structuralChange=" + structuralChange;
    }

    private static String requireText(String value, String parameter) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(parameter + " must not be blank");

        return value.trim();
    }

    private static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0, System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static void deleteDirectory(Path directory) {
        if (directory == null)
            return;

        try (var entries = Files.list(directory)) {
            for (Path entry : entries.toList())
                Files.deleteIfExists(entry);

            Files.deleteIfExists(directory);
        } catch (IOException ignored) {
            // The worker's temporary directory remains the containment boundary.
        }
    }

    private record Column(String name, String normalizedName, int jdbcType, String typeName, int index) {
    }

    private record SnapshotMetadata(JsonNode schema, JsonNode keyColumns, long rowCount) {
    }

    private record StructureDifference(List<String> addedColumns, List<String> removedColumns, List<String> changedColumns, boolean keyColumnsChanged) {
        boolean changed() {
            return keyColumnsChanged || !addedColumns.isEmpty() || !removedColumns.isEmpty() || !changedColumns.isEmpty();
        }
    }

    private record Difference(long added, long removed, long modified, Map<String, Object> samples, int truncatedValues) {
        boolean changed() {
            return added > 0 || removed > 0 || modified > 0;
        }
    }
}
