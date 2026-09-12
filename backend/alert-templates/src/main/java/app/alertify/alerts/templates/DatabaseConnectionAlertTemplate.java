package app.alertify.alerts.templates;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import app.alertify.alerts.AlertEvaluator;
import app.alertify.alerts.AlertExecutionContext;
import app.alertify.alerts.AlertResult;
import app.alertify.alerts.template.annotation.AlertParameter;
import app.alertify.alerts.template.annotation.AlertTemplate;
import app.alertify.alerts.template.annotation.AlertTemplateTag;
import app.alertify.worker.contract.DatabaseConnections;
import app.alertify.worker.contract.DatabaseCredentials;

/**
 * Standard alert that opens a JDBC connection with the credentials of a
 * {@code DB_SECRET} and verifies that the database answers a validity check.
 * The password never appears in the status message or the stored state.
 */
@AlertTemplate(
    nameKey = "alerts.template.databaseConnection.name",
    descriptionKey = "alerts.template.databaseConnection.description",
    tags = @AlertTemplateTag(nameKey = "alerts.templateTag.database", color = "#F59E0B"),
    sourcePath = "app/alertify/alerts/templates/DatabaseConnectionAlertTemplate.java"
)
public final class DatabaseConnectionAlertTemplate implements AlertEvaluator {

    private static final int MAX_TIMEOUT_SECONDS = 3_600;

    @AlertParameter(
        labelKey = "alerts.template.databaseConnection.credentials",
        descriptionKey = "alerts.template.databaseConnection.credentialsDescription",
        bindingAllowed = true,
        order = 1
    )
    private final DatabaseCredentials credentials;

    @AlertParameter(
        labelKey = "alerts.template.databaseConnection.timeout",
        descriptionKey = "alerts.template.databaseConnection.timeoutDescription",
        options = { "1", "3", "5", "10", "30" },
        bindingAllowed = true,
        defaultValue = "5",
        order = 2
    )
    private final int timeoutSeconds;

    public DatabaseConnectionAlertTemplate(DatabaseCredentials credentials, int timeoutSeconds) {
        if (credentials == null)
            throw new IllegalArgumentException("credentials must not be null");

        if (timeoutSeconds <= 0 || timeoutSeconds > MAX_TIMEOUT_SECONDS)
            throw new IllegalArgumentException("timeoutSeconds must be between 1 and " + MAX_TIMEOUT_SECONDS);

        this.credentials = credentials;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public AlertResult evaluate(AlertExecutionContext context) {
        long startedNanos = System.nanoTime();
        try (Connection connection = DatabaseConnections.open(credentials, Duration.ofSeconds(timeoutSeconds))) {
            long connectMs = elapsedMillis(startedNanos);
            boolean valid = connection.isValid(timeoutSeconds);
            long totalLatencyMs = elapsedMillis(startedNanos);
            Map<String, Object> statusMessage = statusMessage(valid, connectMs, totalLatencyMs);
            describeServer(connection, statusMessage);
            if (!valid) {
                statusMessage.put("failureReason", "invalid_connection");
                context.setState(state(false, totalLatencyMs, "invalid_connection"));
                return AlertResult.warn(statusMessage);
            }

            context.setState(state(true, totalLatencyMs, null));
            return AlertResult.success(statusMessage);
        } catch (SQLException exception) {
            long totalLatencyMs = elapsedMillis(startedNanos);
            String failureReason = failureReason(exception);
            Map<String, Object> statusMessage = statusMessage(false, totalLatencyMs, totalLatencyMs);
            statusMessage.put("failureReason", failureReason);
            if (exception.getSQLState() != null)
                statusMessage.put("sqlState", exception.getSQLState());

            if (exception.getMessage() != null && !exception.getMessage().isBlank())
                statusMessage.put("failureMessage", exception.getMessage());

            context.setState(state(false, totalLatencyMs, failureReason));
            return AlertResult.warn(statusMessage);
        }
    }

    private Map<String, Object> statusMessage(boolean connected, long connectMs, long totalLatencyMs) {
        Map<String, Object> statusMessage = new LinkedHashMap<>();
        statusMessage.put("engine", credentials.engine().name());
        statusMessage.put("host", credentials.host());
        statusMessage.put("port", credentials.port());
        statusMessage.put("database", credentials.database());
        statusMessage.put("username", credentials.username());
        statusMessage.put("timeoutSeconds", timeoutSeconds);
        statusMessage.put("connected", connected);
        statusMessage.put("connectMs", connectMs);
        statusMessage.put("totalLatencyMs", totalLatencyMs);
        statusMessage.put("checkedAt", Instant.now().toString());
        return statusMessage;
    }

    private static void describeServer(Connection connection, Map<String, Object> statusMessage) {
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            statusMessage.put("productName", metaData.getDatabaseProductName());
            statusMessage.put("productVersion", metaData.getDatabaseProductVersion());
            statusMessage.put("driverName", metaData.getDriverName());
        } catch (SQLException exception) {
            statusMessage.put("metadataError", exception.getMessage());
        }
    }

    private String state(boolean connected, long totalLatencyMs, String failureReason) {
        String value = "engine=" + credentials.engine() + ";host=" + credentials.host() + ";port=" + credentials.port()
            + ";database=" + credentials.database() + ";connected=" + connected + ";totalLatencyMs=" + totalLatencyMs;
        if (failureReason == null)
            return value;

        return value + ";failureReason=" + failureReason;
    }

    private static String failureReason(SQLException exception) {
        // DriverManager reports a missing driver with SQL state 08001, so check the message before the class.
        if (exception.getMessage() != null && exception.getMessage().contains("No suitable driver"))
            return "driver_missing";

        if (exception instanceof SQLTimeoutException)
            return "timeout";

        if (exception instanceof SQLInvalidAuthorizationSpecException || isAuthenticationState(exception.getSQLState()))
            return "auth_failed";

        if (exception instanceof SQLTransientConnectionException || exception instanceof SQLNonTransientConnectionException
                || (exception.getSQLState() != null && exception.getSQLState().startsWith("08")))
            return "connect_failed";

        return "sql_error";
    }

    private static boolean isAuthenticationState(String sqlState) {
        // 28xxx is the SQL standard class for invalid authorization; MySQL reports 28000, PostgreSQL 28P01.
        return sqlState != null && sqlState.startsWith("28");
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0, System.nanoTime() - startedNanos) / 1_000_000;
    }
}
