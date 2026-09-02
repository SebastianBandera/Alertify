package app.alertify.alerts.templates.devtools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import app.alertify.alerts.AlertExecutionContext;
import app.alertify.alerts.execution.AlertExecutionStatus;

class WritableParameterCopyAlertTemplateTest {

    @Test
    void copiesTheSourceValueIntoTheMutableParameter() {
        var template = new WritableParameterCopyAlertTemplate("new value", "previous value");

        var result = template.evaluate(new AlertExecutionContext());

        assertEquals(AlertExecutionStatus.SUCCESS, result.status());
        assertEquals("new value", result.statusMessage().get("writtenValue"));
    }
}
