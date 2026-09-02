package app.alertify.alerts.templates.devtools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import app.alertify.alerts.AlertExecutionContext;
import app.alertify.alerts.AlertResult;
import app.alertify.alerts.execution.AlertExecutionStatus;

class SimulatedLongRunningAlertTemplateTest {

    @Test
    void reportsTheConfiguredAndEffectiveDelays() throws Exception {
        SimulatedLongRunningAlertTemplate template = new SimulatedLongRunningAlertTemplate(1, true, 0, 0);
        AlertExecutionContext context = new AlertExecutionContext();

        AlertResult result = template.evaluate(context);

        assertEquals(AlertExecutionStatus.SUCCESS, result.status());
        assertEquals(1L, result.statusMessage().get("sleepMilliseconds"));
        assertEquals(true, result.statusMessage().get("randomInitialDelayEnabled"));
        assertEquals(0L, result.statusMessage().get("randomInitialDelaySeconds"));
        assertTrue(context.getState().contains("sleepMilliseconds=1"));
    }

    @Test
    void rejectsInvalidDelayRanges() {
        assertThrows(IllegalArgumentException.class, () -> new SimulatedLongRunningAlertTemplate(-1, false, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new SimulatedLongRunningAlertTemplate(0, true, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new SimulatedLongRunningAlertTemplate(0, true, 2, 1));
    }
}
