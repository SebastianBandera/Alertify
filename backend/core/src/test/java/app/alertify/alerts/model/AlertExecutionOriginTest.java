package app.alertify.alerts.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import app.alertify.alerts.execution.AlertExecutionStatus;
import app.alertify.alerts.execution.AlertExecutionTrigger;

class AlertExecutionOriginTest {
    @Test
    void preservesStructuredPipeParentAndRejectsOtherOrigins() {
        Instant now = Instant.now();
        UUID parent = UUID.randomUUID();
        AlertExecution execution = AlertExecution.result(UUID.randomUUID(), mock(Alert.class), null,
                AlertExecutionStatus.WARN, now, now, now, null, AlertExecutionTrigger.PIPE, "pipe:" + parent);
        execution.recordPipeParent(parent, "check");
        assertEquals(parent, execution.getParentPipeExecutionId());
        assertEquals("check", execution.getParentStepKey());

        AlertExecution manual = AlertExecution.result(UUID.randomUUID(), mock(Alert.class), null,
                AlertExecutionStatus.WARN, now, now, now, null, AlertExecutionTrigger.MANUAL, "admin");
        assertThrows(IllegalArgumentException.class, () -> manual.recordPipeParent(parent, "check"));
    }

    @Test
    void preservesHookNameSnapshotAndRejectsIncompleteOrWrongParents() {
        Instant now = Instant.now();
        UUID parent = UUID.randomUUID();
        AlertExecution hook = AlertExecution.result(UUID.randomUUID(), mock(Alert.class), null,
                AlertExecutionStatus.WARN, now, now, now, null, AlertExecutionTrigger.HOOK, "hook:" + parent);
        hook.recordHookParent(parent, "Example hook");
        assertEquals(parent, hook.getParentHookInvocationId());
        assertEquals("Example hook", hook.getParentHookName());
        assertThrows(IllegalArgumentException.class, () -> hook.recordHookParent(parent, null));
        assertThrows(IllegalArgumentException.class, () -> hook.recordHookParent(null, "Example hook"));
        assertThrows(IllegalArgumentException.class, () -> hook.recordHookParent(parent, " "));
        assertThrows(IllegalArgumentException.class, () -> hook.recordPipeParent(parent, "check"));

        AlertExecution manual = AlertExecution.result(UUID.randomUUID(), mock(Alert.class), null,
                AlertExecutionStatus.SUCCESS, now, now, now, null, AlertExecutionTrigger.MANUAL, "admin");
        assertThrows(IllegalArgumentException.class, () -> manual.recordHookParent(parent, "Example hook"));
        assertThrows(IllegalArgumentException.class, () -> manual.recordPipeParent(parent, null));
        assertThrows(IllegalArgumentException.class, () -> manual.recordPipeParent(null, "check"));
    }
}
