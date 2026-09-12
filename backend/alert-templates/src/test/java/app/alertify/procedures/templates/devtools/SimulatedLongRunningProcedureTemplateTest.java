package app.alertify.procedures.templates.devtools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import app.alertify.procedures.ProcedureExecutionContext;

class SimulatedLongRunningProcedureTemplateTest {

    @Test
    void reportsTheConfiguredAndEffectiveDelays() throws Exception {
        var result = new SimulatedLongRunningProcedureTemplate(1, true, 0, 0)
                .execute(new ProcedureExecutionContext(Instant.now(), Map.of()));

        assertEquals(1L, result.get("sleepMilliseconds").asLong());
        assertTrue(result.get("randomInitialDelayEnabled").asBoolean());
        assertEquals(0L, result.get("randomInitialDelaySeconds").asLong());
    }

    @Test
    void rejectsInvalidDelayRanges() {
        assertThrows(IllegalArgumentException.class, () -> new SimulatedLongRunningProcedureTemplate(-1, false, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new SimulatedLongRunningProcedureTemplate(0, true, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new SimulatedLongRunningProcedureTemplate(0, true, 2, 1));
    }
}
