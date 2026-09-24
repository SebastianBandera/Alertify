package app.alertify.alerts.templates;

import java.math.BigDecimal;
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
 * Runs a prepared SQL query that returns one row and takes the alert result
 * from one textual column: SUCCESS or WARN report that status and ERROR fails
 * the execution, compared ignoring case and surrounding blanks. Any other
 * value is a misconfigured query and fails the execution as well. One textual
 * detail column can optionally be included in the persisted result. SQL text,
 * bound parameter values and credentials are never copied to the execution
 * state or status message.
 */
@AlertTemplate(
    nameKey = "alerts.template.sqlStatus.name",
    descriptionKey = "alerts.template.sqlStatus.description",
    tags = @AlertTemplateTag(nameKey = "alerts.templateTag.database", color = "#F59E0B"),
    sourcePath = "app/alertify/alerts/templates/SqlStatusAlertTemplate.java"
)
public final class SqlStatusAlertTemplate implements AlertEvaluator {

    private static final int MAX_DETAIL_CODE_POINTS = 4_096;
    private static final int MAX_TIMEOUT_SECONDS = 604_800;
    private static final String SUCCESS = "SUCCESS";
    private static final String WARN = "WARN";
    private static final String ERROR = "ERROR";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @AlertParameter(
        labelKey = "alerts.template.sqlStatus.credentials",
        descriptionKey = "alerts.template.sqlStatus.credentialsDescription",
        order = 1,
        allowedSources = { AlertParameterSource.SECRET },
        allowedSecretValueTypes = "DB_SECRET"
    )
    private final DatabaseCredentials credentials;

    @AlertParameter(
        labelKey = "alerts.template.sqlStatus.sql",
        descriptionKey = "alerts.template.sqlStatus.sqlDescription",
        multiline = true,
        order = 2
    )
    private final String sql;

    @AlertParameter(
        labelKey = "alerts.template.sqlStatus.sqlParametersJson",
        descriptionKey = "alerts.template.sqlStatus.sqlParametersJsonDescription",
        defaultValue = "[]",
        multiline = true,
        required = false,
        order = 3
    )
    private final String sqlParametersJson;

    @AlertParameter(
        labelKey = "alerts.template.sqlStatus.statusColumnName",
        descriptionKey = "alerts.template.sqlStatus.statusColumnNameDescription",
        order = 4
    )
    private final String statusColumnName;

    @AlertParameter(
        labelKey = "alerts.template.sqlStatus.detailColumnName",
        descriptionKey = "alerts.template.sqlStatus.detailColumnNameDescription",
        required = false,
        order = 5
    )
    private final String detailColumnName;

    @AlertParameter(
        labelKey = "alerts.template.sqlStatus.alertDescription",
        descriptionKey = "alerts.template.sqlStatus.alertDescriptionDescription",
        required = false,
        order = 6,
        allowedSources = { AlertParameterSource.TEXT, AlertParameterSource.CONFIGURATION }
    )
    private final String description;

    @AlertParameter(
        labelKey = "alerts.template.sqlStatus.timeout",
        descriptionKey = "alerts.template.sqlStatus.timeoutDescription",
        options = { "1", "3", "5", "10", "30", "300", "3600", "86400", "604800" },
        defaultValue = "10",
        order = 7
    )
    private final int timeoutSeconds;

    public SqlStatusAlertTemplate(DatabaseCredentials credentials, String sql, String sqlParametersJson, String statusColumnName, String detailColumnName, String description, int timeoutSeconds) {
        if (credentials == null)
            throw new IllegalArgumentException("credentials must not be null");

        this.credentials = credentials;
        this.sql = requireText(sql, "sql");
        this.sqlParametersJson = sqlParametersJson == null || sqlParametersJson.isBlank() ? "[]" : sqlParametersJson;
        this.statusColumnName = requireText(statusColumnName, "statusColumnName");
        this.detailColumnName = optionalText(detailColumnName);
        this.description = optionalText(description);
        if (timeoutSeconds <= 0 || timeoutSeconds > MAX_TIMEOUT_SECONDS)
            throw new IllegalArgumentException("timeoutSeconds must be between 1 and " + MAX_TIMEOUT_SECONDS);

        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public AlertResult evaluate(AlertExecutionContext context) {
        JsonNode parameters = parseParameters(sqlParametersJson);
        long startedNanos = System.nanoTime();
        QueryResult queryResult;
        try (Connection connection = DatabaseConnections.open(credentials, Duration.ofSeconds(timeoutSeconds))) {
            connection.setReadOnly(true);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(timeoutSeconds);
                bindParameters(statement, parameters);
                try (ResultSet resultSet = statement.executeQuery()) {
                    queryResult = readResult(resultSet);
                }
            }
        } catch (SQLException exception) {
            return sqlWarning(context, exception, elapsedMillis(startedNanos));
        }

        String status = status(queryResult.status());
        context.setState("status=" + status);
        if (ERROR.equals(status))
            throw new SqlStatusFailure(queryResult.detail() == null || queryResult.detail().isBlank()
                    ? "SQL status query reported ERROR" : queryResult.detail());

        Map<String, Object> statusMessage = statusMessage(elapsedMillis(startedNanos));
        statusMessage.put("value", status);
        if (detailColumnName != null) {
            statusMessage.put("detail", queryResult.detail());
            statusMessage.put("detailColumn", detailColumnName);
            statusMessage.put("detailTruncated", queryResult.detailTruncated());
        }
        return WARN.equals(status) ? AlertResult.warn(statusMessage) : AlertResult.success(statusMessage);
    }

    /* The received value is left out of the message: an unexpected column may hold anything. */
    private static String status(String value) {
        String status = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!SUCCESS.equals(status) && !WARN.equals(status) && !ERROR.equals(status))
            throw new IllegalArgumentException("SQL status column must contain SUCCESS, WARN or ERROR");

        return status;
    }

    private QueryResult readResult(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metadata = resultSet.getMetaData();
        int statusColumn = columnIndex(metadata, statusColumnName);
        int detailColumn = detailColumnName == null ? -1 : columnIndex(metadata, detailColumnName);
        if (!resultSet.next())
            throw new IllegalArgumentException("SQL status query must return exactly one row, but returned none");

        String status = resultSet.getString(statusColumn);
        String detail = detailColumn < 0 ? null : resultSet.getString(detailColumn);
        if (resultSet.next())
            throw new IllegalArgumentException("SQL status query must return exactly one row, but returned more than one");

        Detail normalizedDetail = truncateDetail(detail);
        return new QueryResult(status, normalizedDetail.value(), normalizedDetail.truncated());
    }

    private Map<String, Object> statusMessage(long latencyMs) {
        Map<String, Object> statusMessage = new LinkedHashMap<>();
        statusMessage.put("statusColumn", statusColumnName);
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

        context.setState("status=WARN;failure=" + failureReason);
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

    /** Raised when the query itself reports ERROR; the detail column, when set, becomes its message. */
    public static final class SqlStatusFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public SqlStatusFailure(String message) {
            super(message);
        }
    }

    private record Detail(String value, boolean truncated) {
    }

    private record QueryResult(String status, String detail, boolean detailTruncated) {
    }
}
