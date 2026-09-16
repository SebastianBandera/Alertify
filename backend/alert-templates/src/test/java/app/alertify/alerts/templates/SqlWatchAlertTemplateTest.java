package app.alertify.alerts.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import app.alertify.alerts.AlertExecutionContext;
import app.alertify.alerts.AlertResult;
import app.alertify.alerts.execution.AlertExecutionStatus;
import app.alertify.alerts.template.annotation.AlertParameter;
import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.worker.contract.DatabaseConnections;
import app.alertify.worker.contract.DatabaseCredentials;
import app.alertify.worker.contract.DatabaseEngine;
import tools.jackson.databind.JsonNode;

class SqlWatchAlertTemplateTest {

    @Test
    void declaresWritableBinarySnapshotForConfigurationOrSecret() throws ReflectiveOperationException {
        AlertParameter snapshot = parameter("snapshot");

        assertEquals(List.of(AlertParameterSource.CONFIGURATION, AlertParameterSource.SECRET), List.of(snapshot.allowedSources()));
        assertEquals(List.of("BINARY"), List.of(snapshot.allowedConfigurationValueTypes()));
        assertEquals(List.of("BINARY"), List.of(snapshot.allowedSecretValueTypes()));
        assertTrue(snapshot.writableBindingRequired());
        assertEquals(byte[].class, SqlWatchAlertTemplate.class.getDeclaredField("snapshot").getType());
        assertEquals("10", parameter("timeoutSeconds").defaultValue());
        assertTrue(List.of(parameter("timeoutSeconds").options()).contains("604800"));
        assertEquals("20", parameter("maxReportedRows").defaultValue());
    }

    @Test
    void initializesComparesAndRollsTheSnapshot() throws Exception {
        DatabaseCredentials credentials = databaseWithRows();
        SqlWatchAlertTemplate first = template(credentials, "SELECT id, name FROM watched", new byte[0], 20);

        AlertResult initialized = first.evaluate(new AlertExecutionContext());
        byte[] initialSnapshot = snapshot(first);

        assertEquals(AlertExecutionStatus.SUCCESS, initialized.status());
        assertEquals(true, initialized.statusMessage().get("initialized"));
        assertEquals(2L, initialized.statusMessage().get("rows"));
        assertTrue(initialSnapshot.length > 0);

        SqlWatchAlertTemplate unchanged = template(credentials, "SELECT name, id FROM watched", initialSnapshot, 20);
        AlertResult same = unchanged.evaluate(new AlertExecutionContext());
        assertEquals(AlertExecutionStatus.SUCCESS, same.status());
        assertEquals(0L, same.statusMessage().get("addedRows"));
        assertEquals(0L, same.statusMessage().get("removedRows"));
        assertEquals(0L, same.statusMessage().get("modifiedRows"));
        assertArrayEquals(initialSnapshot, snapshot(unchanged));

        execute(credentials, "UPDATE watched SET name = 'changed' WHERE id = 1");
        execute(credentials, "DELETE FROM watched WHERE id = 2");
        execute(credentials, "INSERT INTO watched(id, name, payload) VALUES (3, 'new', X'03')");

        SqlWatchAlertTemplate changed = template(credentials, "SELECT id, name FROM watched", initialSnapshot, 20);
        AlertResult warning = changed.evaluate(new AlertExecutionContext());
        byte[] rolledSnapshot = snapshot(changed);

        assertEquals(AlertExecutionStatus.WARN, warning.status());
        assertEquals(1L, warning.statusMessage().get("addedRows"));
        assertEquals(1L, warning.statusMessage().get("removedRows"));
        assertEquals(1L, warning.statusMessage().get("modifiedRows"));
        assertTrue(warning.statusMessage().containsKey("samples"));
        assertFalse(java.util.Arrays.equals(initialSnapshot, rolledSnapshot));

        AlertResult stableAgain = template(credentials, "SELECT id, name FROM watched", rolledSnapshot, 20)
                .evaluate(new AlertExecutionContext());
        assertEquals(AlertExecutionStatus.SUCCESS, stableAgain.status());
    }

    @Test
    void initializesAnEmptyResultAndRebaselinesStructuralChanges() throws Exception {
        DatabaseCredentials credentials = databaseWithRows();
        SqlWatchAlertTemplate empty = template(credentials, "SELECT id, name FROM watched WHERE 1 = 0", new byte[0], 20);

        AlertResult initialized = empty.evaluate(new AlertExecutionContext());

        assertEquals(AlertExecutionStatus.SUCCESS, initialized.status());
        assertEquals(0L, initialized.statusMessage().get("rows"));
        byte[] emptySnapshot = snapshot(empty);

        SqlWatchAlertTemplate structural = template(credentials, "SELECT id, name, 1 AS extra FROM watched WHERE 1 = 0", emptySnapshot, 20);
        AlertResult warning = structural.evaluate(new AlertExecutionContext());

        assertEquals(AlertExecutionStatus.WARN, warning.status());
        assertEquals(true, warning.statusMessage().get("structuralChange"));
        assertEquals(List.of("extra"), warning.statusMessage().get("addedColumns"));

        AlertResult stableAgain = template(credentials, "SELECT extra, name, id FROM (SELECT id, name, 1 AS extra FROM watched WHERE 1 = 0)", snapshot(structural), 20)
                .evaluate(new AlertExecutionContext());
        assertEquals(AlertExecutionStatus.SUCCESS, stableAgain.status());
    }

    @Test
    void rejectsDuplicateKeysCorruptSnapshotsAndInvalidLimits() throws Exception {
        DatabaseCredentials credentials = databaseWithRows();
        SqlWatchAlertTemplate duplicate = template(credentials, "SELECT 1 AS id, 'a' AS name UNION ALL SELECT 1 AS id, 'b' AS name", new byte[0], 20);
        SqlWatchAlertTemplate corrupt = template(credentials, "SELECT id, name FROM watched", new byte[] { 1, 2, 3 }, 20);

        assertThrows(IllegalArgumentException.class, () -> duplicate.evaluate(new AlertExecutionContext()));
        assertThrows(IllegalArgumentException.class, () -> corrupt.evaluate(new AlertExecutionContext()));
        assertThrows(IllegalArgumentException.class, () -> new SqlWatchAlertTemplate(
                credentials, "SELECT id FROM watched", "[]", "[]", new byte[0], 20, null, 10));
        assertThrows(IllegalArgumentException.class, () -> template(credentials, "SELECT id FROM watched", new byte[0], 101));
        assertThrows(IllegalArgumentException.class, () -> new SqlWatchAlertTemplate(
                credentials, "SELECT id FROM watched", "[]", "[\"id\"]", new byte[0], 20, null, 604_801));
    }

    @Test
    void reportsBinaryValuesOnlyAsLengthAndDigest() throws Exception {
        DatabaseCredentials credentials = databaseWithRows();
        SqlWatchAlertTemplate first = new SqlWatchAlertTemplate(
                credentials, "SELECT id, payload FROM watched WHERE id = 1", "[]", "[\"id\"]", new byte[0], 20, null, 10);
        first.evaluate(new AlertExecutionContext());
        execute(credentials, "UPDATE watched SET payload = X'01020304' WHERE id = 1");

        SqlWatchAlertTemplate changed = new SqlWatchAlertTemplate(
                credentials, "SELECT id, payload FROM watched WHERE id = 1", "[]", "[\"id\"]", snapshot(first), 20, null, 10);
        AlertResult result = changed.evaluate(new AlertExecutionContext());

        assertEquals(AlertExecutionStatus.WARN, result.status());
        @SuppressWarnings("unchecked")
        Map<String, Object> samples = (Map<String, Object>) result.statusMessage().get("samples");
        @SuppressWarnings("unchecked")
        List<Map<String, JsonNode>> modified = (List<Map<String, JsonNode>>) samples.get("modified");
        JsonNode payload = modified.getFirst().get("current").get("payload");
        assertEquals(4L, payload.get("length").longValue());
        assertEquals(64, payload.get("sha256").stringValue().length());
        assertFalse(result.statusMessage().toString().contains("01020304"));
    }

    @Test
    void sanitizesDatabaseFailuresWithoutEchoingSqlParametersOrCredentials() {
        DatabaseCredentials credentials = new DatabaseCredentials(
                DatabaseEngine.OTHER, "db.local", 1, "alertify", "app", "password-marker",
                "jdbc:nonexistent-sql-watch://db.local/alertify");
        SqlWatchAlertTemplate template = new SqlWatchAlertTemplate(
                credentials, "SELECT 'sql-marker'", "[\"parameter-marker\"]", "[\"id\"]", new byte[0], 20, null, 3);

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> template.evaluate(new AlertExecutionContext()));

        assertFalse(exception.getMessage().contains("password-marker"));
        assertFalse(exception.getMessage().contains("parameter-marker"));
        assertFalse(exception.getMessage().contains("sql-marker"));
    }

    private static SqlWatchAlertTemplate template(DatabaseCredentials credentials, String sql, byte[] snapshot, int maximumRows) {
        return new SqlWatchAlertTemplate(
                credentials, sql, "[]", "[\"id\"]", snapshot, maximumRows, "Watch test", 10);
    }

    private static DatabaseCredentials databaseWithRows() throws Exception {
        DatabaseCredentials credentials = credentials();
        execute(credentials, "CREATE TABLE watched(id INT PRIMARY KEY, name VARCHAR(100), payload VARBINARY)");
        execute(credentials, "INSERT INTO watched(id, name, payload) VALUES (1, 'one', X'01')");
        execute(credentials, "INSERT INTO watched(id, name, payload) VALUES (2, 'two', X'02')");
        return credentials;
    }

    private static DatabaseCredentials credentials() {
        return new DatabaseCredentials(
                DatabaseEngine.OTHER, "localhost", 1, "test", "sa", "test-password",
                "jdbc:h2:mem:sql_watch_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    }

    private static void execute(DatabaseCredentials credentials, String sql) throws Exception {
        try (Connection connection = DatabaseConnections.open(credentials); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static byte[] snapshot(SqlWatchAlertTemplate template) throws ReflectiveOperationException {
        Field field = SqlWatchAlertTemplate.class.getDeclaredField("snapshot");
        field.setAccessible(true);
        return (byte[]) field.get(template);
    }

    private static AlertParameter parameter(String fieldName) throws ReflectiveOperationException {
        return SqlWatchAlertTemplate.class.getDeclaredField(fieldName).getAnnotation(AlertParameter.class);
    }
}
