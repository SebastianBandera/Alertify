package app.alertify.codex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import app.alertify.worker.ai.grpc.AiSettings;

class AiModuleHistorySettingsTest {
    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void propagatesTheConfiguredValue(boolean enabled) {
        var client = mock(AiWorkerClient.class);
        when(client.update(any())).thenAnswer(call -> call.getArgument(0));
        var service = new AiModuleService(client, mock(BrowserExtensionPackageService.class), "", "");
        var result = service.update(new AiModuleService.SettingsRequest(true, "CODEX", "model", "low", "", "ON_USE", "", "low", enabled, !enabled));
        var sent = ArgumentCaptor.forClass(AiSettings.class);
        verify(client).update(sent.capture());
        assertThat(sent.getValue().getConversationHistorySearchEnabled()).isEqualTo(enabled);
        assertThat(sent.getValue().getMemoryEnabled()).isEqualTo(!enabled);
        assertThat(result.memoryEnabled()).isEqualTo(!enabled);
        assertThat(result.conversationHistorySearchEnabled()).isEqualTo(enabled);
    }
}
