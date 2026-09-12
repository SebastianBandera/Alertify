package app.alertify.procedures.templates.devtools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.procedures.ProcedureExecutionContext;

class WritableParameterCopyProcedureTemplateTest {

    @Test
    void copiesTheSourceValueIntoTheMutableParameter() {
        var result = new WritableParameterCopyProcedureTemplate("new value", "previous value")
                .execute(context(AlertParameterSource.TEXT, AlertParameterSource.CONFIGURATION));

        assertEquals("new value", result.get("writtenValue").asString());
    }

    @Test
    void omitsTheWrittenValueWhenTheDestinationIsBoundToASecret() {
        var result = new WritableParameterCopyProcedureTemplate("new secret", "previous secret")
                .execute(context(AlertParameterSource.TEXT, AlertParameterSource.SECRET));

        assertTrue(result.isObject());
        assertTrue(result.isEmpty());
    }

    @Test
    void omitsTheWrittenValueWhenTheSourceIsBoundToASecret() {
        var result = new WritableParameterCopyProcedureTemplate("new secret", "previous value")
                .execute(context(AlertParameterSource.SECRET, AlertParameterSource.CONFIGURATION));

        assertTrue(result.isObject());
        assertTrue(result.isEmpty());
    }

    private static ProcedureExecutionContext context(AlertParameterSource source, AlertParameterSource destination) {
        return new ProcedureExecutionContext(Instant.now(), Map.of(
                "sourceValue", source,
                "writableValue", destination
        ));
    }
}
