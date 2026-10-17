package app.alertify.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import app.alertify.ai.AiToolCatalog;
import app.alertify.ai.localization.AiLocalizationSourceService;
import app.alertify.codex.AiWorkerClient;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.realtime.AdminEventPublisher;
import app.alertify.worker.ai.grpc.*;
import tools.jackson.databind.json.JsonMapper;

class AiConversationHistoryToolsTest {
    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();

    @ParameterizedTest
    @ValueSource(strings = { "READ_ONLY", "PLAN", "DIRECT" })
    void advertisesBothReadOnlyToolsAndUsesTrustedIdentityInEveryMode(String mode) {
        var fixture = fixture(mode, true);
        var definitions = fixture.sent().getFirst().getStart().getToolsList().stream()
                .filter(tool -> tool.getName().startsWith("alertify_conversation_")).toList();
        assertThat(definitions).hasSize(2).allSatisfy(tool -> {
            assertThat(tool.getReadOnly()).isTrue();
            assertThat(tool.getIdempotentHint()).isTrue();
            assertThat(JSON.readTree(tool.getInputSchema()).path("additionalProperties").booleanValue()).isFalse();
        });
        when(fixture.history.search("subject-1", 7L, "{\"query\":\"certificate\"}"))
                .thenReturn(new AiConversationHistory.SearchResult(List.of(), null, "historical"));
        fixture.call(AiConversationHistory.SEARCH_TOOL, "{\"query\":\"certificate\"}");
        assertThat(fixture.result().getFailed()).isFalse();
        verify(fixture.history).search("subject-1", 7L, "{\"query\":\"certificate\"}");
        fixture.call(AiConversationHistory.MESSAGES_TOOL, "{\"conversationId\":42}");
        verify(fixture.history).messages("subject-1", 7L, "{\"conversationId\":42}");
    }

    @ParameterizedTest
    @ValueSource(strings = { "READ_ONLY", "PLAN", "DIRECT" })
    void disabledToolsAreAbsentAndForgedCallsAreDeniedWithoutReadingHistory(String mode) {
        var fixture = fixture(mode, false);
        assertThat(fixture.sent().getFirst().getStart().getToolsList()).noneMatch(tool -> tool.getName().startsWith("alertify_conversation_"));
        for (String name : List.of(AiConversationHistory.SEARCH_TOOL, AiConversationHistory.MESSAGES_TOOL)) {
            fixture.call(name, "{}");
            assertThat(fixture.result().getFailed()).isTrue();
            assertThat(fixture.result().getDiagnosticCode()).isEqualTo("CONVERSATION_HISTORY_SEARCH_DISABLED");
            var receipt = JSON.readTree(fixture.result().getOperationMetadataJson());
            assertThat(receipt.path("phase").stringValue()).isEqualTo("PRE_EXECUTION");
            assertThat(receipt.path("status").stringValue()).isEqualTo("REJECTED");
        }
        verifyNoInteractions(fixture.history);
        verify(fixture.persistence, times(2)).toolDenied(any(), any(), eq("CONVERSATION_HISTORY_SEARCH_DISABLED"));
    }

    @Test
    void disablingMidTurnAlsoRemovesDefinitionsFromTheNextGeneration() {
        var fixture = fixture("READ_ONLY", true);
        fixture.settings.set(settings(false));
        fixture.call(AiConversationHistory.SEARCH_TOOL, "{}");
        assertThat(fixture.result().getDiagnosticCode()).isEqualTo("CONVERSATION_HISTORY_SEARCH_DISABLED");
        fixture.call(AiConversationHistory.MESSAGES_TOOL, "{}");
        assertThat(fixture.result().getDiagnosticCode()).isEqualTo("CONVERSATION_HISTORY_SEARCH_DISABLED");
        fixture.call("alertify_request_reasoning_upgrade", "{\"reason\":\"complex query\"}");
        assertThat(fixture.sent().stream().filter(ChatClientMessage::hasStart).toList()).hasSize(2)
                .last().satisfies(message -> assertThat(message.getStart().getToolsList())
                        .noneMatch(tool -> tool.getName().startsWith("alertify_conversation_")));
        verifyNoInteractions(fixture.history);
    }

    @Test
    void settingsFailureDeniesBothToolsWithoutLeakingTheError() {
        var fixture = fixture("READ_ONLY", true);
        when(fixture.worker.settings()).thenThrow(new IllegalStateException("PRIVATE-WORKER-ERROR"));
        for (String name : List.of(AiConversationHistory.SEARCH_TOOL, AiConversationHistory.MESSAGES_TOOL)) {
            fixture.call(name, "{}");
            assertThat(fixture.result().getDiagnosticCode()).isEqualTo("CONVERSATION_HISTORY_SETTINGS_UNAVAILABLE");
            assertThat(fixture.result().getOutput()).doesNotContain("PRIVATE");
        }
        verifyNoInteractions(fixture.history);
    }

    private Fixture fixture(String mode, boolean enabled) {
        var worker = mock(AiWorkerClient.class);
        var catalog = mock(AiToolCatalog.class);
        var persistence = mock(AiChatPersistence.class);
        var localization = mock(AiLocalizationSourceService.class);
        var authentication = mock(Authentication.class);
        var history = mock(AiConversationHistory.class);
        var settings = new AtomicReference<>(settings(enabled));
        when(authentication.getName()).thenReturn("subject-1");
        when(localization.resolveLocale(any())).thenReturn("en");
        when(worker.state()).thenReturn(ModuleStateResponse.newBuilder().setEnabled(true).setSessionStatus("ACTIVE").build());
        when(worker.settings()).thenAnswer(call -> settings.get());
        when(persistence.start(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(new AiChatPersistence.StartedTurn(7L, 8L));
        when(persistence.tool(any(), anyLong(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
                anyBoolean(), anyBoolean(), anyBoolean(), any(), anyBoolean())).thenAnswer(call -> {
                    var turn = mock(AiTurn.class);
                    when(turn.getId()).thenReturn(8L);
                    var invocation = new AiToolInvocation(turn, call.getArgument(1), call.getArgument(2), call.getArgument(3), call.getArgument(4),
                            call.getArgument(5), call.getArgument(6), call.getArgument(7), call.getArgument(8), call.getArgument(9),
                            call.getArgument(10), call.getArgument(11), call.getArgument(12), call.getArgument(13));
                    ReflectionTestUtils.setField(invocation, "id", 99L);
                    return invocation;
                });
        var handle = mock(AiWorkerClient.ChatHandle.class);
        ArgumentCaptor<Consumer<ChatWorkerMessage>> consumer = ArgumentCaptor.forClass(Consumer.class);
        when(worker.chat(consumer.capture(), any(), any())).thenReturn(handle);
        var service = new AiChatService(worker, catalog, persistence, mock(AiConversationRepository.class), mock(AiTurnRepository.class),
                mock(AiMessageRepository.class), mock(AiToolInvocationRepository.class), localization, mock(AdminEventPublisher.class),
                mock(ApplicationEventLogger.class), JSON, history);
        service.submit(authentication, new AiChatService.SubmitRequest(null, "find earlier context", mode, "ASK", "en"));
        return new Fixture(worker, persistence, history, settings, consumer.getValue(), handle);
    }

    private static AiSettings settings(boolean enabled) {
        return AiSettings.newBuilder().setModel("model").setReasoningEffort("low").setConversationHistorySearchEnabled(enabled).build();
    }

    private record Fixture(AiWorkerClient worker, AiChatPersistence persistence, AiConversationHistory history, AtomicReference<AiSettings> settings,
            Consumer<ChatWorkerMessage> consumer, AiWorkerClient.ChatHandle handle) {
        void call(String name, String arguments) {
            consumer.accept(ChatWorkerMessage.newBuilder().setToolCall(ChatToolCall.newBuilder().setCallId("call")
                    .setName(name).setArguments(arguments)).build());
        }

        List<ChatClientMessage> sent() {
            var messages = ArgumentCaptor.forClass(ChatClientMessage.class);
            verify(handle, atLeastOnce()).send(messages.capture());
            return messages.getAllValues();
        }

        ChatToolResult result() {
            return sent().stream().filter(ChatClientMessage::hasToolResult).toList().getLast().getToolResult();
        }
    }
}
