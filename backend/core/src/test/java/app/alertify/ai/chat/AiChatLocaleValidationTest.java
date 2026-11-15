package app.alertify.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.core.Authentication;

import app.alertify.ai.AiToolCatalog;
import app.alertify.ai.localization.AiLocalizationSourceService;
import app.alertify.ai.memory.AiMemoryService;
import app.alertify.codex.AiWorkerClient;
import app.alertify.codex.AiWorkerException;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.realtime.AdminEventPublisher;
import tools.jackson.databind.json.JsonMapper;

class AiChatLocaleValidationTest {
    @TempDir
    Path directory;

    @Test
    void rejectsInvalidLocalesAsClientErrorsBeforeCreatingATurnOrContactingTheWorker() {
        AiWorkerClient worker = mock(AiWorkerClient.class);
        AiChatPersistence persistence = mock(AiChatPersistence.class);
        AiChatService service = new AiChatService(worker, mock(AiToolCatalog.class), persistence,
                mock(AiConversationRepository.class), mock(AiTurnRepository.class), mock(AiMessageRepository.class),
                mock(AiToolInvocationRepository.class), new AiLocalizationSourceService(directory.toString()),
                mock(AdminEventPublisher.class), mock(ApplicationEventLogger.class), JsonMapper.builder().build(),
                mock(AiConversationHistory.class), mock(AiMemoryService.class), mock(AiPatchReadback.class));
        Authentication authentication = mock(Authentication.class);
        for (String locale : List.of("a-".repeat(15000) + "a", "a".repeat(65), "en--US", "fr-FR")) {
            var request = new AiChatService.SubmitRequest(null, "Controlled request", "READ_ONLY", "ASK", locale);
            assertThatThrownBy(() -> service.submit(authentication, request))
                    .isInstanceOfSatisfying(AiWorkerException.class, failure -> assertThat(failure.getCode()).isEqualTo("AI_INVALID_REQUEST"))
                    .hasMessage("Unsupported frontend locale")
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(worker, persistence, authentication);
    }
}
