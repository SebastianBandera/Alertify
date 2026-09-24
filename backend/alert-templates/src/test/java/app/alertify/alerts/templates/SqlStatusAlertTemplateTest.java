package app.alertify.alerts.templates;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import app.alertify.alerts.AlertExecutionContext;
import app.alertify.alerts.AlertResult;
import app.alertify.alerts.execution.AlertExecutionStatus;
import app.alertify.alerts.template.annotation.AlertParameter;
import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.alerts.template.annotation.AlertTemplate;
import app.alertify.worker.contract.DatabaseCredentials;
import app.alertify.worker.contract.DatabaseEngine;

class SqlStatusAlertTemplateTest {

    private static final String STATUS_QUERY = "SELECT CAST(? AS VARCHAR) AS alert_status";
    private static final String STATUS_AND_DETAIL_QUERY = "SELECT CAST(? AS VARCHAR) AS alert_status, CAST(? AS VARCHAR) AS alert_detail";

    @Test
    void declaresLocalizedTemplateAndTheSqlThresholdParametersExceptTheThreshold() throws ReflectiveOperationException {
        AlertTemplate metadata = SqlStatusAlertTemplate.class.getAnnotation(AlertTemplate.class);

        assertEquals("alerts.template.sqlStatus.name", metadata.nameKey());
        assertEquals("alerts.template.sqlStatus.description", metadata.descriptionKey());
        assertEquals("app/alertify/alerts/templates/SqlStatusAlertTemplate.java", metadata.sourcePath());

        List<String> orderedFields = Arrays.stream(SqlStatusAlertTemplate.class.getDeclaredFields())
            .filter(field -> field.isAnnotationPresent(AlertParameter.class))
            .sorted((left, right) -> Integer.compare(
                left.getAnnotation(AlertParameter.class).order(), right.getAnnotation(AlertParameter.class).order()))
            .map(java.lang.reflect.Field::getName)
            .toList();
        assertEquals(
            List.of("credentials", "sql", "sqlParametersJson", "statusColumnName", "detailColumnName", "description", "timeoutSeconds"),
            orderedFields
        );

        AlertParameter credentials = parameter("credentials");
        assertEquals(List.of(AlertParameterSource.SECRET), List.of(credentials.allowedSources()));
        assertEquals(List.of("DB_SECRET"), List.of(credentials.allowedSecretValueTypes()));
        assertTrue(parameter("sql").multiline());
        assertEquals("[]", parameter("sqlParametersJson").defaultValue());
        assertFalse(parameter("sqlParametersJson").required());
        assertFalse(parameter("detailColumnName").required());
        assertEquals("10", parameter("timeoutSeconds").defaultValue());
        assertTrue(List.of(parameter("timeoutSeconds").options()).contains("604800"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "SUCCESS     | SUCCESS",
        "WARN        | WARN",
        "warn        | WARN",
        "'  Success ' | SUCCESS"
    })
    void reportsTheStatusReadFromTheColumn(String value, AlertExecutionStatus expected) {
        AlertExecutionContext context = new AlertExecutionContext();

        AlertResult result = template(STATUS_QUERY, "[\"" + value + "\"]", null).evaluate(context);

        assertEquals(expected, result.status());
        assertEquals(expected.name(), result.statusMessage().get("value"));
        assertEquals("alert_status", result.statusMessage().get("statusColumn"));
        assertEquals("status=" + expected, context.getState());
    }

    @Test
    void failsTheExecutionWithTheDetailWhenTheQueryReportsError() {
        SqlStatusAlertTemplate template = template(STATUS_AND_DETAIL_QUERY, "[\"error\",\"Replication lag above limit\"]", "alert_detail");

        SqlStatusAlertTemplate.SqlStatusFailure failure = assertThrows(
            SqlStatusAlertTemplate.SqlStatusFailure.class, () -> template.evaluate(new AlertExecutionContext())
        );

        assertEquals("Replication lag above limit", failure.getMessage());
    }

    @Test
    void failsTheExecutionWithADefaultMessageWhenErrorComesWithoutDetail() {
        SqlStatusAlertTemplate template = template(STATUS_QUERY, "[\"ERROR\"]", null);

        SqlStatusAlertTemplate.SqlStatusFailure failure = assertThrows(
            SqlStatusAlertTemplate.SqlStatusFailure.class, () -> template.evaluate(new AlertExecutionContext())
        );

        assertEquals("SQL status query reported ERROR", failure.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = { "[\"OK\"]", "[\"\"]", "[null]", "[\"secret-marker\"]" })
    void rejectsValuesOtherThanTheSupportedStatusesWithoutEchoingThem(String parameters) {
        SqlStatusAlertTemplate template = template(STATUS_QUERY, parameters, null);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class, () -> template.evaluate(new AlertExecutionContext())
        );

        assertEquals("SQL status column must contain SUCCESS, WARN or ERROR", exception.getMessage());
        assertFalse(exception.getMessage().contains("secret-marker"));
    }

    @Test
    void includesConfiguredDetailAndTruncatesItAt4096CodePoints() {
        String detail = "á".repeat(4_100);
        SqlStatusAlertTemplate template = template(STATUS_AND_DETAIL_QUERY, "[\"WARN\",\"" + detail + "\"]", "alert_detail");

        AlertResult result = template.evaluate(new AlertExecutionContext());

        assertEquals(AlertExecutionStatus.WARN, result.status());
        assertEquals(4_096, ((String) result.statusMessage().get("detail")).codePointCount(0, 4_096));
        assertEquals("alert_detail", result.statusMessage().get("detailColumn"));
        assertEquals(true, result.statusMessage().get("detailTruncated"));
    }

    @Test
    void includesNullDetailWithoutChangingTheStatus() {
        SqlStatusAlertTemplate template = template(
            "SELECT 'SUCCESS' AS alert_status, CAST(NULL AS VARCHAR) AS alert_detail", "[]", "alert_detail"
        );

        AlertResult result = template.evaluate(new AlertExecutionContext());

        assertEquals(AlertExecutionStatus.SUCCESS, result.status());
        assertTrue(result.statusMessage().containsKey("detail"));
        assertNull(result.statusMessage().get("detail"));
        assertEquals(false, result.statusMessage().get("detailTruncated"));
    }

    @Test
    void requiresExactlyOneResultRowAndTheConfiguredColumn() {
        SqlStatusAlertTemplate empty = template("SELECT 'SUCCESS' AS alert_status WHERE 1 = 0", "[]", null);
        SqlStatusAlertTemplate multiple = template(
            "SELECT X AS alert_status FROM (VALUES ('SUCCESS'), ('WARN')) T(X)", "[]", null
        );
        SqlStatusAlertTemplate missing = template("SELECT 'SUCCESS' AS other_status", "[]", null);

        assertThrows(IllegalArgumentException.class, () -> empty.evaluate(new AlertExecutionContext()));
        assertThrows(IllegalArgumentException.class, () -> multiple.evaluate(new AlertExecutionContext()));
        assertThrows(IllegalArgumentException.class, () -> missing.evaluate(new AlertExecutionContext()));
    }

    @Test
    void rejectsInvalidOrStructuredSqlParametersWithoutEchoingTheirValues() {
        SqlStatusAlertTemplate invalidJson = template(STATUS_QUERY, "[secret-marker", null);
        SqlStatusAlertTemplate structured = template(STATUS_QUERY, "[{\"secret-marker\":true}]", null);

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
            "jdbc:nonexistent-sql-status://db.local/alertify"
        );
        SqlStatusAlertTemplate template = new SqlStatusAlertTemplate(
            credentials, "SELECT 'sql-marker'", "[\"parameter-marker\"]", "alert_status", null, null, 3
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

        assertDoesNotThrow(() -> new SqlStatusAlertTemplate(credentials, "SELECT 1", "[]", "status", null, null, 604_800));

        assertThrows(IllegalArgumentException.class, () -> new SqlStatusAlertTemplate(null, "SELECT 1", "[]", "status", null, null, 3));
        assertThrows(IllegalArgumentException.class, () -> new SqlStatusAlertTemplate(credentials, " ", "[]", "status", null, null, 3));
        assertThrows(IllegalArgumentException.class, () -> new SqlStatusAlertTemplate(credentials, "SELECT 1", "[]", " ", null, null, 3));
        assertThrows(IllegalArgumentException.class, () -> new SqlStatusAlertTemplate(credentials, "SELECT 1", "[]", "status", null, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new SqlStatusAlertTemplate(credentials, "SELECT 1", "[]", "status", null, null, 604_801));
    }

    private static SqlStatusAlertTemplate template(String sql, String parameters, String detailColumn) {
        return new SqlStatusAlertTemplate(credentials(), sql, parameters, "alert_status", detailColumn, "Status test", 3);
    }

    private static DatabaseCredentials credentials() {
        return new DatabaseCredentials(
            DatabaseEngine.OTHER, "localhost", 1, "test", "sa", "test-password",
            "jdbc:h2:mem:sql_status_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"
        );
    }

    private static AlertParameter parameter(String fieldName) throws ReflectiveOperationException {
        return SqlStatusAlertTemplate.class.getDeclaredField(fieldName).getAnnotation(AlertParameter.class);
    }
}
