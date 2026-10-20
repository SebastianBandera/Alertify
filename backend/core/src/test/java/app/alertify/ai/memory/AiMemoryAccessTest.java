package app.alertify.ai.memory;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import app.alertify.ai.AiToolExecutor;
import app.alertify.ai.tools.MemoryAiTools;
import app.alertify.codex.AiWorkerClient;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.worker.ai.grpc.AiSettings;

class AiMemoryAccessTest {
    @AfterEach
    void clearIdentity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void directFacadeCallsCannotBypassTheSwitchAndReenablingKeepsTheSameOwner() {
        var worker = mock(AiWorkerClient.class);
        var memories = mock(AiMemoryService.class);
        var tools = new MemoryAiTools(memories, new AiToolExecutor(mock(ApplicationEventLogger.class)), worker);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("owner", "unused", List.of()));
        when(worker.settings()).thenReturn(AiSettings.newBuilder().setMemoryEnabled(false).build());
        assertThatThrownBy(() -> tools.search("lesson", null, null)).isInstanceOf(AiMemoryAccess.Unavailable.class);
        assertThatThrownBy(() -> tools.save("lesson", "content", null)).isInstanceOf(AiMemoryAccess.Unavailable.class);
        assertThatThrownBy(() -> tools.delete("lesson", 0)).isInstanceOf(AiMemoryAccess.Unavailable.class);
        verifyNoInteractions(memories);
        when(worker.settings()).thenReturn(AiSettings.newBuilder().setMemoryEnabled(true).build());
        tools.search("lesson", null, null);
        verify(memories).search("owner", "lesson", null, null);
    }
}
