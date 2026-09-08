package app.alertify.hooks.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import app.alertify.hooks.model.HookTargetStatus;
import app.alertify.hooks.model.HookTargetType;
import tools.jackson.databind.json.JsonMapper;

class HookInvocationTargetResponseTest {

    @Test
    void exposesOnlyTheSanitizedPublicTargetContract() throws Exception {
        String json = JsonMapper.builder().build().writeValueAsString(new HookInvocationTargetResponse(
                HookTargetType.ALERT, "Production health", 0, HookTargetStatus.ERROR, UUID.randomUUID()
        ));

        assertThat(json).contains("type", "resourceName", "position", "status", "executionId")
                .doesNotContain("resourceId", "outcome", "startedAt", "finishedAt", "errorCode", "result", "parameter", "stackTrace", "secret");
    }
}
