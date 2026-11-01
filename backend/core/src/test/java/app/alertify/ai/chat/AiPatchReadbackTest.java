package app.alertify.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;

import app.alertify.ai.AiToolDescriptor;
import tools.jackson.databind.json.JsonMapper;

class AiPatchReadbackTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final AiChatPersistence persistence = mock(AiChatPersistence.class);
    private final ToolCallback callback = mock(ToolCallback.class);
    private final AiToolInvocation patch = mock(AiToolInvocation.class);
    private final AiToolInvocation read = mock(AiToolInvocation.class);
    private final AiPatchReadback readback = new AiPatchReadback(json, persistence);
    private final String committed = "{\"id\":42,\"version\":8,\"name\":\"kept\",\"value\":60}";

    @BeforeEach
    void setup() {
        when(patch.getId()).thenReturn(10L);
        when(patch.getGeneration()).thenReturn(1L);
        when(patch.getToolName()).thenReturn("alertify_configuration_patch");
        when(read.getId()).thenReturn(11L);
        when(persistence.tool(anyLong(), anyLong(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
                anyBoolean(), anyBoolean(), anyBoolean(), any(), anyBoolean())).thenReturn(read);
    }

    @Test
    void readsAutomaticallyAndRecordsTheLinkedInvocation() {
        when(callback.call("{\"id\":42}")).thenReturn(committed);
        var result = run();
        assertThat(result.evidence().transactionComparison()).isEqualTo("SERVER_VERIFIED");
        assertThat(result.evidence().readComparison()).isEqualTo("MATCHED");
        assertThat(result.definition().path("value").intValue()).isEqualTo(60);
        verify(persistence).tool(eq(2L), eq(1L), eq("patch-read:10"), eq("alertify_configuration_get"), eq("{\"id\":42}"),
                eq("INTERNAL"), eq(true), eq(true), eq(true), eq(false), eq(true), eq(false), eq(""), eq(false));
        verify(persistence).toolSucceeded(11L, committed);
    }

    @Test
    void failedReadDoesNotReplayOrMarkThePatchFailed() {
        when(callback.call(any())).thenThrow(new IllegalStateException("PRIVATE-MARKER"));
        var result = run();
        assertThat(result.evidence().readStatus()).isEqualTo("FAILED");
        assertThat(result.evidence().transactionComparison()).isEqualTo("SERVER_VERIFIED");
        assertThat(result.definition()).isNull();
        verify(persistence, never()).toolFailed(eq(10L), any(), any());
        verify(callback, times(1)).call(any());
        verify(persistence).toolFailed(eq(11L), argThat(value -> !value.contains("PRIVATE-MARKER")), eq("PATCH_READ_FAILED"));
    }

    @Test
    void detectsConcurrentEditAfterCommit() {
        when(callback.call(any())).thenReturn(committed.replace("\"version\":8", "\"version\":9"));
        assertThat(run().evidence().readComparison()).isEqualTo("CHANGED_AFTER_PATCH");
    }

    @Test
    void detectsUnexpectedDifferenceWithoutChangingTheWriteOutcome() {
        when(callback.call(any())).thenReturn(committed.replace("\"value\":60", "\"value\":61"));
        assertThat(run().evidence().readComparison()).isEqualTo("DIFFERENT");
        verify(persistence).toolSucceeded(eq(11L), any());
    }

    @Test
    void neverUsesAnUnavailableOrWritableReadTool() {
        var result = readback.read(1L, 2L, "en", patch, "{\"id\":42}", committed, Map.of());
        assertThat(result.evidence().readStatus()).isEqualTo("UNAVAILABLE");
        verifyNoInteractions(callback);
    }

    private AiPatchReadback.Result run() {
        var descriptor = mock(AiToolDescriptor.class);
        when(descriptor.name()).thenReturn("alertify_configuration_get");
        when(descriptor.readOnly()).thenReturn(true);
        when(descriptor.callback()).thenReturn(callback);
        return readback.read(1L, 2L, "en", patch, "{\"id\":42}", committed, Map.of(descriptor.name(), descriptor));
    }
}
