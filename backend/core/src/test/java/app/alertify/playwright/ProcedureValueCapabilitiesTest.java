package app.alertify.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import app.alertify.procedures.execution.ProcedureExecutionOrchestrator;
import app.alertify.procedures.execution.ProcedureExecutionPreparationService;
import app.alertify.procedures.execution.ProcedureInvocationRegistry;
import tools.jackson.databind.json.JsonMapper;

class ProcedureValueCapabilitiesTest {
    @Test
    void neverDispatchesAnInactiveExpiredOrReleasedCapability() {
        var registry = new ProcedureInvocationRegistry();
        var preparation = mock(ProcedureExecutionPreparationService.class);
        try (var executions = new ProcedureExecutionOrchestrator(preparation, null, null, null, null, registry, null, null, null, null, JsonMapper.builder().build(), null, null)) {
            UUID root = UUID.randomUUID();
            String declaration = "{\"procedureId\":7,\"pointer\":\"/code\"}";
            String inactive = executions.prepareAlertValue(declaration, root, Instant.now().plusSeconds(60));
            assertThat(executions.invokeToken(inactive).hasFailure()).isTrue();
            registry.register(root, Instant.now().plusSeconds(60));
            executions.releaseValues(root);
            assertThat(executions.invokeToken(inactive).hasFailure()).isTrue();
            String expired = executions.prepareAlertValue(declaration, root, Instant.now().minusSeconds(1));
            assertThat(executions.invokeToken(expired).hasFailure()).isTrue();
            assertThatThrownBy(() -> executions.prepareAlertValue("{\"procedureId\":7,\"pointer\":\"/invalid~x\"}", root, Instant.now().plusSeconds(60))).hasMessageContaining("Invalid");
            executions.releaseValues(root);
            verifyNoInteractions(preparation);
        }
    }
}
