package app.alertify.alerts.templates.devtools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import app.alertify.alerts.AlertExecutionContext;
import app.alertify.alerts.AlertResult;
import app.alertify.alerts.execution.AlertExecutionStatus;

class SimulatedResultAlertTemplateTest {

    @Test
    void succeedsWithTheConfiguredMessage() {
        AlertResult result = new SimulatedResultAlertTemplate("SUCCESS", "all good").evaluate(new AlertExecutionContext());

        assertEquals(AlertExecutionStatus.SUCCESS, result.status());
        assertEquals("SUCCESS", result.statusMessage().get("status"));
        assertEquals("all good", result.statusMessage().get("message"));
    }

    @Test
    void warnsWithTheConfiguredMessage() {
        AlertResult result = new SimulatedResultAlertTemplate("WARN", "watch out").evaluate(new AlertExecutionContext());

        assertEquals(AlertExecutionStatus.WARN, result.status());
        assertEquals("watch out", result.statusMessage().get("message"));
    }

    @Test
    void failsByThrowingWithTheConfiguredMessage() {
        SimulatedResultAlertTemplate template = new SimulatedResultAlertTemplate("ERROR", "boom");

        SimulatedResultAlertTemplate.SimulatedFailure failure = assertThrows(SimulatedResultAlertTemplate.SimulatedFailure.class, () -> template.evaluate(new AlertExecutionContext()));

        assertEquals("boom", failure.getMessage());
    }

    @Test
    void treatsAMissingMessageAsEmpty() {
        AlertResult result = new SimulatedResultAlertTemplate("SUCCESS", null).evaluate(new AlertExecutionContext());

        assertEquals("", result.statusMessage().get("message"));
    }

    @Test
    void rejectsUnknownStatuses() {
        assertThrows(IllegalArgumentException.class, () -> new SimulatedResultAlertTemplate("MAYBE", "x"));
        assertThrows(IllegalArgumentException.class, () -> new SimulatedResultAlertTemplate(null, "x"));
    }
}
