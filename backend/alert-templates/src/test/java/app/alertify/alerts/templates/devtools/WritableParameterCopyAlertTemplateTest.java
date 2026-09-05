package app.alertify.alerts.templates.devtools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

import app.alertify.alerts.AlertExecutionContext;
import app.alertify.alerts.execution.AlertExecutionStatus;
import app.alertify.alerts.template.annotation.AlertParameterSource;

class WritableParameterCopyAlertTemplateTest {

    @Test
    void copiesTheSourceValueIntoTheMutableParameter() {
        var template = new WritableParameterCopyAlertTemplate("new value", "previous value");

        var result = template.evaluate(context(
                AlertParameterSource.TEXT, AlertParameterSource.CONFIGURATION
        ));

        assertEquals(AlertExecutionStatus.SUCCESS, result.status());
        assertEquals("new value", result.statusMessage().get("writtenValue"));
    }

    @Test
    void omitsTheWrittenValueWhenTheDestinationIsBoundToASecret() {
        var template = new WritableParameterCopyAlertTemplate("new secret", "previous secret");

        var result = template.evaluate(context(
                AlertParameterSource.TEXT, AlertParameterSource.SECRET
        ));

        assertEquals(AlertExecutionStatus.SUCCESS, result.status());
        assertEquals(Map.of(), result.statusMessage());
    }

    @Test
    void omitsTheWrittenValueWhenTheSourceIsBoundToASecret() {
        var template = new WritableParameterCopyAlertTemplate("new secret", "previous value");

        var result = template.evaluate(context(
                AlertParameterSource.SECRET, AlertParameterSource.CONFIGURATION
        ));

        assertEquals(AlertExecutionStatus.SUCCESS, result.status());
        assertEquals(Map.of(), result.statusMessage());
    }

    private static AlertExecutionContext context(
        AlertParameterSource source,
        AlertParameterSource destination
    ) {
        return new AlertExecutionContext("", Map.of(
                "sourceValue", source,
                "writableValue", destination
        ));
    }
}
