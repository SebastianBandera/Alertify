package app.alertify.alerts.templates;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import app.alertify.alerts.AlertEvaluator;
import app.alertify.alerts.AlertExecutionContext;
import app.alertify.alerts.AlertResult;
import app.alertify.alerts.template.annotation.AlertParameter;
import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.alerts.template.annotation.AlertTemplate;
import app.alertify.alerts.template.annotation.AlertTemplateTag;
import app.alertify.worker.contract.DatabaseConnections;
import app.alertify.worker.contract.DatabaseCredentials;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs a prepared SQL query that returns one row, compares one numeric column
 * with a threshold and optionally includes one textual detail column in the
 * persisted result. SQL text, bound parameter values and credentials are never
 * copied to the execution state or status message.
 */
@AlertTemplate(
    nameKey = "alerts.template.sqlThreshold.name",
    descriptionKey = "alerts.template.sqlThreshold.description",
    tags = @AlertTemplateTag(nameKey = "alerts.templateTag.database", color = "#F59E0B"),
    sourcePath = "app/alertify/alerts/templates/SqlThresholdAlertTemplate.java"
)
public final class SqlThresholdAlertTemplate implements AlertEvaluator {

    private static final int MAX_DETAIL_CODE_POINTS = 4_096;
    private static final int MAX_TIMEOUT_SECONDS = 604_800;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @AlertParameter(
        labelKey = "alerts.template.sqlThreshold.credentials",
        descriptionKey = "alerts.template.sqlThreshold.credentialsDescription",
        order = 1,
        allowedSources = { AlertParameterSource.SECRET },
        allowedSecretValueTypes = "DB_SECRET"
    )
    private final DatabaseCredentials credentials;

    @AlertParameter(
        labelKey = "alerts.template.sqlThreshold.sql",
        descriptionKey = "alerts.template.sqlThreshold.sqlDescription",
        multiline = true,
        order = 2
    )
    private final String sql;

    @AlertParameter(
        labelKey = "alerts.template.sqlThreshold.sqlParametersJson",
        descriptionKey = "alerts.template.sqlThreshold.sqlParametersJsonDescription",
        defaultValue = "[]",
        multiline = true,
        required = false,
        order = 3
    )
    private final String sqlParametersJson;

    @AlertParameter(
        labelKey = "alerts.template.sqlThreshold.numericColumnName",
        descriptionKey = "alerts.template.sqlThreshold.numericColumnNameDescription",
        order = 4,
        allowedSources = { AlertParameterSource.TEXT, AlertParameterSource.CONFIGURATION }
    )
    private final String numericColumnName;

    @AlertParameter(
        labelKey = "alerts.template.sqlThreshold.detailColumnName",
        descriptionKey = "alerts.template.sqlThreshold.detailColumnNameDescription",
        required = false,
        order = 5,
        allowedSources = { AlertParameterSource.TEXT, AlertParameterSource.CONFIGURATION }
    )
    private final String detailColumnName;

    @AlertParameter(
        labelKey = "alerts.template.sqlThreshold.threshold",
        descriptionKey = "alerts.template.sqlThreshold.thresholdDescription",
        order = 6,
        allowedSources = { AlertParameterSource.TEXT, AlertParameterSource.CONFIGURATION }
    )
    private final long threshold;

    @AlertParameter(
        labelKey = "alerts.template.sqlThreshold.thresholdType",
        descriptionKey = "alerts.template.sqlThreshold.thresholdTypeDescription",
        options = { "warn_if_bigger", "warn_if_lower", "warn_if_equal", "warn_if_distinct" },
        bindingAllowed = false,
        defaultValue = "warn_if_bigger",
        order = 7
    )
    private final String thresholdType;

    @AlertParameter(
        labelKey = "alerts.template.sqlThreshold.alertDescription",
        descriptionKey = "alerts.template.sqlThreshold.alertDescriptionDescription",
        required = false,
        order = 8,
        allowedSources = { AlertParameterSource.TEXT, AlertParameterSource.CONFIGURATION }
    )
    private final String description;

    @AlertParameter(
        labelKey = "alerts.template.sqlThreshold.timeout",
        descriptionKey = "alerts.template.sqlThreshold.timeoutDescription",
        options = { "1", "3", "5", "10", "30", "300", "3600", "86400", "604800" },
        defaultValue = "10",
        order = 9,
        allowedSources = { AlertParameterSource.TEXT, AlertParameterSource.CONFIGURATION }
    )
    private final int timeoutSeconds;

    private final ThresholdComparison comparison;

    public SqlThresholdAlertTemplate(DatabaseCredentials credentials, String sql, String sqlParametersJson, String numericColumnName, String detailColumnName, long threshold, String thresholdType, String description, int timeoutSeconds) {
        if (credentials == null)
            throw new IllegalArgumentException("credentials must not be null");

        this.credentials = credentials;
        this.sql = requireText(sql, "sql");
        this.sqlParametersJson = sqlParametersJson == null || sqlParametersJson.isBlank() ? "[]" : sqlParametersJson;
        this.numericColumnName = requireText(numericColumnName, "numericColumnName");
        this.detailColumnName = optionalText(detailColumnName);
        this.threshold = threshold;
        this.thresholdType = requireText(thresholdType, "thresholdType").toLowerCase(Locale.ROOT);
        this.comparison = ThresholdComparison.parse(this.thresholdType);
        this.description = optionalText(description);
        if (timeoutSeconds <= 0 || timeoutSeconds > MAX_TIMEOUT_SECONDS)
            throw new IllegalArgumentException("timeoutSeconds must be between 1 and " + MAX_TIMEOUT_SECONDS);

        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public AlertResult evaluate(AlertExecutionContext context) {
        JsonNode parameters = parseParameters(sqlParametersJson);
        long startedNanos = System.nanoTime();
        try (Connection connection = DatabaseConnections.open(credentials, Duration.ofSeconds(timeoutSeconds))) {
            connection.setReadOnly(true);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(timeoutSeconds);
                bindParameters(statement, parameters);
                try (ResultSet resultSet = statement.executeQuery()) {
                    QueryResult queryResult = readResult(resultSet);
                    long latencyMs = elapsedMillis(startedNanos);
                    Map<String, Object> statusMessage = statusMessage(latencyMs);
                    statusMessage.put("value", queryResult.value());
                    if (detailColumnName != null) {
                        statusMessage.put("detail", queryResult.detail());
                        statusMessage.put("detailColumn", detailColumnName);
                        statusMessage.put("detailTruncated", queryResult.detailTruncated());
                    }

                    boolean warn = comparison.warn(queryResult.value(), threshold);
                    context.setState("value=" + queryResult.value() + ";threshold=" + threshold + ";thresholdType=" + thresholdType + ";status=" + (warn ? "WARN" : "SUCCESS"));
                    return warn ? AlertResult.warn(statusMessage) : AlertResult.success(statusMessage);
                }
            }
        } catch (SQLException exception) {
            return sqlWarning(context, exception, elapsedMillis(startedNanos));
        }
    }

    private QueryResult readResult(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metadata = resultSet.getMetaData();
        int numericColumn = columnIndex(metadata, numericColumnName);
        int detailColumn = detailColumnName == null ? -1 : columnIndex(metadata, detailColumnName);
        if (!resultSet.next())
            throw new IllegalArgumentException("SQL threshold query must return exactly one row, but returned none");

        long value = numericValue(resultSet.getObject(numericColumn));
        String detail = detailColumn < 0 ? null : resultSet.getString(detailColumn);
        if (resultSet.next())
            throw new IllegalArgumentException("SQL threshold query must return exactly one row, but returned more than one");

        Detail normalizedDetail = truncateDetail(detail);
        return new QueryResult(value, normalizedDetail.value(), normalizedDetail.truncated());
    }

    private Map<String, Object> statusMessage(long latencyMs) {
        Map<String, Object> statusMessage = new LinkedHashMap<>();
        statusMessage.put("valueColumn", numericColumnName);
        statusMessage.put("threshold", threshold);
        statusMessage.put("thresholdType", thresholdType);
        if (description != null)
            statusMessage.put("description", description);

        statusMessage.put("timeoutSeconds", timeoutSeconds);
        statusMessage.put("latencyMs", latencyMs);
        statusMessage.put("checkedAt", Instant.now().toString());
        return statusMessage;
    }

    private AlertResult sqlWarning(AlertExecutionContext context, SQLException exception, long latencyMs) {
        String failureReason = failureReason(exception);
        Map<String, Object> statusMessage = statusMessage(latencyMs);
        statusMessage.put("failureReason", failureReason);
        if (exception.getSQLState() != null)
            statusMessage.put("sqlState", exception.getSQLState());

        context.setState("threshold=" + threshold + ";thresholdType=" + thresholdType + ";status=WARN;failure=" + failureReason);
        return AlertResult.warn(statusMessage);
    }

    private static JsonNode parseParameters(String configured) {
        JsonNode root;
        try {
            root = JSON.readTree(configured);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("sqlParametersJson must be a valid JSON array");
        }
        if (root == null || !root.isArray())
            throw new IllegalArgumentException("sqlParametersJson must be a JSON array");

        for (int index = 0; index < root.size(); index++) {
            JsonNode value = root.get(index);
            if (!(value.isNull() || value.isString() || value.isBoolean() || value.isNumber()))
                throw new IllegalArgumentException("sqlParametersJson item " + index + " must be a scalar JSON value or null");
        }
        return root;
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

    private static int columnIndex(ResultSetMetaData metadata, String configuredName) throws SQLException {
        int match = -1;
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            if (!configuredName.equalsIgnoreCase(metadata.getColumnLabel(index)))
                continue;

            if (match >= 0)
                throw new IllegalArgumentException("SQL query contains more than one column named '" + configuredName + "'");

            match = index;
        }
        if (match < 0)
            throw new IllegalArgumentException("SQL query does not contain column '" + configuredName + "'");

        return match;
    }

    private static long numericValue(Object value) {
        if (value == null)
            throw new IllegalArgumentException("SQL threshold numeric column must not be null");

        try {
            if (value instanceof BigInteger integer)
                return integer.longValueExact();

            if (value instanceof BigDecimal decimal)
                return decimal.longValueExact();

            if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
                return ((Number) value).longValue();

            if (value instanceof Number number)
                return new BigDecimal(number.toString()).longValueExact();

            if (value instanceof CharSequence text)
                return Long.parseLong(text.toString().trim());
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException("SQL threshold numeric column must contain an integer representable as long");
        }
        throw new IllegalArgumentException("SQL threshold numeric column must contain a numeric value");
    }

    private static Detail truncateDetail(String value) {
        if (value == null)
            return new Detail(null, false);

        int codePoints = value.codePointCount(0, value.length());
        if (codePoints <= MAX_DETAIL_CODE_POINTS)
            return new Detail(value, false);

        return new Detail(value.substring(0, value.offsetByCodePoints(0, MAX_DETAIL_CODE_POINTS)), true);
    }

    private static String failureReason(SQLException exception) {
        if (exception instanceof SQLTimeoutException)
            return "timeout";

        if (exception instanceof SQLInvalidAuthorizationSpecException || isAuthenticationState(exception.getSQLState()))
            return "auth_failed";

        if (exception instanceof SQLTransientConnectionException || exception instanceof SQLNonTransientConnectionException
                || exception.getSQLState() != null && exception.getSQLState().startsWith("08"))
            return "connection_failure";

        return "query_failure";
    }

    private static boolean isAuthenticationState(String sqlState) {
        return sqlState != null && sqlState.startsWith("28");
    }

    private static String requireText(String value, String parameter) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(parameter + " must not be blank");

        return value.trim();
    }

    private static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0, System.nanoTime() - startedNanos) / 1_000_000;
    }

    private enum ThresholdComparison {
        WARN_IF_BIGGER,
        WARN_IF_LOWER,
        WARN_IF_EQUAL,
        WARN_IF_DISTINCT;

        static ThresholdComparison parse(String value) {
            return switch (value) {
                case "warn_if_bigger" -> WARN_IF_BIGGER;
                case "warn_if_lower" -> WARN_IF_LOWER;
                case "warn_if_equal" -> WARN_IF_EQUAL;
                case "warn_if_distinct" -> WARN_IF_DISTINCT;
                default -> throw new IllegalArgumentException("Unsupported thresholdType: " + value);
            };
        }

        boolean warn(long value, long threshold) {
            return switch (this) {
                case WARN_IF_BIGGER -> value > threshold;
                case WARN_IF_LOWER -> value < threshold;
                case WARN_IF_EQUAL -> value == threshold;
                case WARN_IF_DISTINCT -> value != threshold;
            };
        }
    }

    private record Detail(String value, boolean truncated) {
    }

    private record QueryResult(long value, String detail, boolean detailTruncated) {
    }
}
