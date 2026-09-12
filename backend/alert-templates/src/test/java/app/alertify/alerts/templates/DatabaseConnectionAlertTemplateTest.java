package app.alertify.alerts.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import app.alertify.alerts.AlertExecutionContext;
import app.alertify.alerts.AlertResult;
import app.alertify.alerts.execution.AlertExecutionStatus;
import app.alertify.alerts.template.annotation.AlertParameter;
import app.alertify.alerts.template.annotation.AlertTemplate;
import app.alertify.worker.contract.DatabaseCredentials;
import app.alertify.worker.contract.DatabaseEngine;

class DatabaseConnectionAlertTemplateTest {

    @Test
    void declaresLocalizedTemplateAndParameterMetadata() throws ReflectiveOperationException {
        AlertTemplate metadata = DatabaseConnectionAlertTemplate.class.getAnnotation(AlertTemplate.class);

        assertEquals("alerts.template.databaseConnection.name", metadata.nameKey());
        assertEquals("alerts.template.databaseConnection.description", metadata.descriptionKey());
        assertEquals("app/alertify/alerts/templates/DatabaseConnectionAlertTemplate.java", metadata.sourcePath());

        AlertParameter credentials = parameter("credentials");
        AlertParameter timeout = parameter("timeoutSeconds");
        assertEquals(1, credentials.order());
        assertTrue(credentials.bindingAllowed());
        assertEquals(DatabaseCredentials.class, DatabaseConnectionAlertTemplate.class.getDeclaredField("credentials").getType());
        assertEquals(2, timeout.order());
        assertEquals("5", timeout.defaultValue());
        assertEquals(List.of("1", "3", "5", "10", "30"), List.of(timeout.options()));
    }

    @Test
    void warnsWithoutExposingThePasswordWhenNoDriverAcceptsTheUrl() {
        DatabaseCredentials credentials = new DatabaseCredentials(
                DatabaseEngine.OTHER, "db.local", 5432, "alertify", "app", "hunter2", "jdbc:nonexistent-engine://db.local/alertify"
        );
        AlertExecutionContext context = new AlertExecutionContext();

        AlertResult result = new DatabaseConnectionAlertTemplate(credentials, 3).evaluate(context);

        assertEquals(AlertExecutionStatus.WARN, result.status());
        assertEquals(false, result.statusMessage().get("connected"));
        assertEquals("driver_missing", result.statusMessage().get("failureReason"));
        assertEquals("db.local", result.statusMessage().get("host"));
        assertFalse(result.statusMessage().toString().contains("hunter2"));
        assertFalse(context.getState().contains("hunter2"));
        assertTrue(context.getState().contains("failureReason=driver_missing"));
    }

    @Test
    void rejectsInvalidConstructorArguments() {
        DatabaseCredentials credentials = new DatabaseCredentials(
                DatabaseEngine.POSTGRESQL, "db.local", 5432, "alertify", "app", "pw", null
        );

        assertThrows(IllegalArgumentException.class, () -> new DatabaseConnectionAlertTemplate(null, 3));
        assertThrows(IllegalArgumentException.class, () -> new DatabaseConnectionAlertTemplate(credentials, 0));
        assertThrows(IllegalArgumentException.class, () -> new DatabaseConnectionAlertTemplate(credentials, 3_601));
    }

    private static AlertParameter parameter(String fieldName) throws ReflectiveOperationException {
        return DatabaseConnectionAlertTemplate.class.getDeclaredField(fieldName).getAnnotation(AlertParameter.class);
    }
}
