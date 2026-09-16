package app.alertify.alerts.templates;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import app.alertify.alerts.AlertExecutionContext;
import app.alertify.alerts.AlertResult;
import app.alertify.alerts.execution.AlertExecutionStatus;
import app.alertify.alerts.template.annotation.AlertParameter;
import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.alerts.template.annotation.AlertTemplate;
import app.alertify.worker.contract.DatabaseCredentials;
import app.alertify.worker.contract.DatabaseEngine;

class SqlThresholdAlertTemplateTest {

    @Test
    void declaresLocalizedTemplateAndParameterMetadata() throws ReflectiveOperationException {
        AlertTemplate metadata = SqlThresholdAlertTemplate.class.getAnnotation(AlertTemplate.class);

        assertEquals("alerts.template.sqlThreshold.name", metadata.nameKey());
        assertEquals("alerts.template.sqlThreshold.description", metadata.descriptionKey());
        assertEquals("app/alertify/alerts/templates/SqlThresholdAlertTemplate.java", metadata.sourcePath());

        AlertParameter credentials = parameter("credentials");
        assertEquals(1, credentials.order());
        assertEquals(List.of(AlertParameterSource.SECRET), List.of(credentials.allowedSources()));
        assertEquals(List.of("DB_SECRET"), List.of(credentials.allowedSecretValueTypes()));

        assertTrue(parameter("sql").multiline());
        assertEquals("[]", parameter("sqlParametersJson").defaultValue());
        assertFalse(parameter("sqlParametersJson").required());
        assertFalse(parameter("detailColumnName").required());
        assertEquals(long.class, SqlThresholdAlertTemplate.class.getDeclaredField("threshold").getType());
        assertEquals(
            List.of("warn_if_bigger", "warn_if_lower", "warn_if_equal", "warn_if_distinct"),
            List.of(parameter("thresholdType").options())
        );
        assertFalse(parameter("thresholdType").bindingAllowed());
        assertEquals("warn_if_bigger", parameter("thresholdType").defaultValue());
        assertEquals("10", parameter("timeoutSeconds").defaultValue());
        assertTrue(List.of(parameter("timeoutSeconds").options()).contains("604800"));
    }

    @ParameterizedTest
    @CsvSource({
        "warn_if_bigger, 11, 10, WARN",
        "warn_if_bigger, 10, 10, SUCCESS",
        "warn_if_lower, 9, 10, WARN",
        "warn_if_lower, 10, 10, SUCCESS",
        "warn_if_equal, 10, 10, WARN",
        "warn_if_equal, 11, 10, SUCCESS",
        "warn_if_distinct, 11, 10, WARN",
        "warn_if_distinct, 10, 10, SUCCESS"
    })
    void appliesEveryThresholdComparison(String thresholdType, long value, long threshold, AlertExecutionStatus expected) {
        AlertExecutionContext context = new AlertExecutionContext();
        SqlThresholdAlertTemplate template = template(
            "SELECT CAST(? AS BIGINT) AS alert_value",
            "[" + value + "]", "alert_value", null, threshold, thresholdType
        );

        AlertResult result = template.evaluate(context);

        assertEquals(expected, result.status());
        assertEquals(value, result.statusMessage().get("value"));
        assertEquals("alert_value", result.statusMessage().get("valueColumn"));
        assertTrue(context.getState().contains("status=" + expected));
    }

    @Test
    void includesConfiguredDetailAndTruncatesItAt4096CodePoints() {
        String detail = "á".repeat(4_100);
        SqlThresholdAlertTemplate template = template(
            "SELECT CAST(? AS BIGINT) AS alert_value, CAST(? AS VARCHAR) AS alert_detail",
            "[12,\"" + detail + "\"]", "alert_value", "alert_detail", 10, "warn_if_bigger"
        );

        AlertResult result = template.evaluate(new AlertExecutionContext());

        assertEquals(AlertExecutionStatus.WARN, result.status());
        assertEquals(4_096, ((String) result.statusMessage().get("detail")).codePointCount(0, 4_096));
        assertEquals("alert_detail", result.statusMessage().get("detailColumn"));
        assertEquals(true, result.statusMessage().get("detailTruncated"));
    }

    @Test
    void includesNullDetailWithoutChangingTheThresholdResult() {
        SqlThresholdAlertTemplate template = template(
            "SELECT 5 AS alert_value, CAST(NULL AS VARCHAR) AS alert_detail",
            "[]", "alert_value", "alert_detail", 10, "warn_if_bigger"
        );

        AlertResult result = template.evaluate(new AlertExecutionContext());

        assertEquals(AlertExecutionStatus.SUCCESS, result.status());
        assertTrue(result.statusMessage().containsKey("detail"));
        assertNull(result.statusMessage().get("detail"));
        assertEquals(false, result.statusMessage().get("detailTruncated"));
    }

    @Test
    void requiresExactlyOneResultRow() {
        SqlThresholdAlertTemplate empty = template(
            "SELECT 1 AS alert_value WHERE 1 = 0", "[]", "alert_value", null, 0, "warn_if_bigger"
        );
        SqlThresholdAlertTemplate multiple = template(
            "SELECT X AS alert_value FROM (VALUES (1), (2)) T(X)", "[]", "alert_value", null, 0, "warn_if_bigger"
        );

        assertThrows(IllegalArgumentException.class, () -> empty.evaluate(new AlertExecutionContext()));
        assertThrows(IllegalArgumentException.class, () -> multiple.evaluate(new AlertExecutionContext()));
    }

    @Test
    void rejectsMissingNullAndNonNumericValueColumns() {
        SqlThresholdAlertTemplate missing = template(
            "SELECT 1 AS other_value", "[]", "alert_value", null, 0, "warn_if_bigger"
        );
        SqlThresholdAlertTemplate nullValue = template(
            "SELECT CAST(NULL AS BIGINT) AS alert_value", "[]", "alert_value", null, 0, "warn_if_bigger"
        );
        SqlThresholdAlertTemplate textValue = template(
            "SELECT 'not-a-number' AS alert_value", "[]", "alert_value", null, 0, "warn_if_bigger"
        );

        assertThrows(IllegalArgumentException.class, () -> missing.evaluate(new AlertExecutionContext()));
        assertThrows(IllegalArgumentException.class, () -> nullValue.evaluate(new AlertExecutionContext()));
        assertThrows(IllegalArgumentException.class, () -> textValue.evaluate(new AlertExecutionContext()));
    }

    @Test
    void rejectsInvalidOrStructuredSqlParametersWithoutEchoingTheirValues() {
        SqlThresholdAlertTemplate invalidJson = template(
            "SELECT 1 AS alert_value", "[secret-marker", "alert_value", null, 0, "warn_if_bigger"
        );
        SqlThresholdAlertTemplate structured = template(
            "SELECT 1 AS alert_value", "[{\"secret-marker\":true}]", "alert_value", null, 0, "warn_if_bigger"
        );

        IllegalArgumentException invalidException = assertThrows(
            IllegalArgumentException.class, () -> invalidJson.evaluate(new AlertExecutionContext())
        );
        IllegalArgumentException structuredException = assertThrows(
            IllegalArgumentException.class, () -> structured.evaluate(new AlertExecutionContext())
        );
        assertFalse(invalidException.getMessage().contains("secret-marker"));
        assertFalse(structuredException.getMessage().contains("secret-marker"));
    }

    @Test
    void turnsJdbcFailuresIntoSanitizedWarnings() {
        DatabaseCredentials credentials = new DatabaseCredentials(
            DatabaseEngine.OTHER, "db.local", 1, "alertify", "app", "password-marker",
            "jdbc:nonexistent-sql-threshold://db.local/alertify"
        );
        SqlThresholdAlertTemplate template = new SqlThresholdAlertTemplate(
            credentials, "SELECT 'sql-marker'", "[\"parameter-marker\"]", "alert_value", null,
            0, "warn_if_bigger", null, 3
        );
        AlertExecutionContext context = new AlertExecutionContext();

        AlertResult result = template.evaluate(context);

        assertEquals(AlertExecutionStatus.WARN, result.status());
        assertEquals("connection_failure", result.statusMessage().get("failureReason"));
        String persisted = result.statusMessage() + context.getState();
        assertFalse(persisted.contains("password-marker"));
        assertFalse(persisted.contains("parameter-marker"));
        assertFalse(persisted.contains("sql-marker"));
    }

    @Test
    void rejectsInvalidConstructorArguments() {
        DatabaseCredentials credentials = credentials();

        assertDoesNotThrow(() -> new SqlThresholdAlertTemplate(
            credentials, "SELECT 1", "[]", "value", null, 0, "warn_if_bigger", null, 604_800
        ));

        assertThrows(IllegalArgumentException.class, () -> new SqlThresholdAlertTemplate(
            null, "SELECT 1", "[]", "value", null, 0, "warn_if_bigger", null, 3
        ));
        assertThrows(IllegalArgumentException.class, () -> new SqlThresholdAlertTemplate(
            credentials, " ", "[]", "value", null, 0, "warn_if_bigger", null, 3
        ));
        assertThrows(IllegalArgumentException.class, () -> new SqlThresholdAlertTemplate(
            credentials, "SELECT 1", "[]", " ", null, 0, "warn_if_bigger", null, 3
        ));
        assertThrows(IllegalArgumentException.class, () -> new SqlThresholdAlertTemplate(
            credentials, "SELECT 1", "[]", "value", null, 0, "unknown", null, 3
        ));
        assertThrows(IllegalArgumentException.class, () -> new SqlThresholdAlertTemplate(
            credentials, "SELECT 1", "[]", "value", null, 0, "warn_if_bigger", null, 0
        ));
        assertThrows(IllegalArgumentException.class, () -> new SqlThresholdAlertTemplate(
            credentials, "SELECT 1", "[]", "value", null, 0, "warn_if_bigger", null, 604_801
        ));
    }

    private static SqlThresholdAlertTemplate template(String sql, String parameters, String numericColumn, String detailColumn, long threshold, String thresholdType) {
        return new SqlThresholdAlertTemplate(
            credentials(), sql, parameters, numericColumn, detailColumn,
            threshold, thresholdType, "Threshold test", 3
        );
    }

    private static DatabaseCredentials credentials() {
        return new DatabaseCredentials(
            DatabaseEngine.OTHER, "localhost", 1, "test", "sa", "test-password",
            "jdbc:h2:mem:sql_threshold_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"
        );
    }

    private static AlertParameter parameter(String fieldName) throws ReflectiveOperationException {
        return SqlThresholdAlertTemplate.class.getDeclaredField(fieldName).getAnnotation(AlertParameter.class);
    }
}
