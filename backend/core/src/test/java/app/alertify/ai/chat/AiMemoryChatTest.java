package app.alertify.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

import app.alertify.ai.*;
import app.alertify.ai.localization.AiLocalizationSourceService;
import app.alertify.ai.memory.AiMemoryService;
import app.alertify.ai.tools.MemoryAiTools;
import app.alertify.codex.AiWorkerClient;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.realtime.AdminEventPublisher;
import app.alertify.worker.ai.grpc.*;
import tools.jackson.databind.json.JsonMapper;

class AiMemoryChatTest {
    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
    private static final String SAVE_ARGUMENTS = "{\"key\":\"test.lesson\",\"content\":\"verified correction\",\"version\":null}";

    @ParameterizedTest
    @ValueSource(strings = { "READ_ONLY", "PLAN", "DIRECT" })
    void loadsTheIndexAndPermittedToolsAndSearchesWithTrustedIdentityWhenOtherChatSearchIsDisabled(String mode) {
        var fixture = fixture(mode, "ASK");
        var start = fixture.sent().getFirst().getStart();
        assertThat(start.getInstructions()).contains("test.lesson", "reference keys and dates only", "proactively", "verified correction");
        assertThat(start.getInstructions()).doesNotContain("PRIVATE-MEMORY-CONTENT");
        fixture.call("alertify_load_tool_group", "{\"group\":\"MEMORY\"}");
        assertThat(fixture.result().getAdditionalToolsList()).hasSize("DIRECT".equals(mode) ? 3 : 1);
        fixture.call(MemoryAiTools.SEARCH_TOOL, "{\"query\":\"lesson\",\"limit\":null,\"cursor\":null}");
        assertThat(fixture.result().getFailed()).isFalse();
        verify(fixture.memories).search("actor", "lesson", null, null);
        assertThat(fixture.result().getOutput()).contains("PRIVATE-MEMORY-CONTENT");
        assertThat(fixture.result().getOperationMetadataJson()).doesNotContain("PRIVATE-MEMORY-CONTENT");
    }

    @ParameterizedTest
    @ValueSource(strings = { "READ_ONLY", "PLAN" })
    void rejectsForcedMemoryWritesOutsideDirectMode(String mode) {
        var fixture = fixture(mode, "ASK");
        fixture.call(MemoryAiTools.SAVE_TOOL, SAVE_ARGUMENTS);
        assertThat(fixture.result().getDiagnosticCode()).isEqualTo("TOOL_NOT_ALLOWED");
        fixture.call(MemoryAiTools.DELETE_TOOL, "{\"key\":\"test.lesson\",\"version\":0}");
        assertThat(fixture.result().getDiagnosticCode()).isEqualTo("TOOL_NOT_ALLOWED");
        verify(fixture.memories, never()).save(any(), any(), any(), any(), any());
        verify(fixture.memories, never()).delete(any(), any(), anyLong());
    }

    @Test
    void askSaveWaitsForApprovalAndThenExecutesWithTheActualOwnerAndConversation() {
        var fixture = fixture("DIRECT", "ASK");
        fixture.call(MemoryAiTools.SAVE_TOOL, SAVE_ARGUMENTS);
        verify(fixture.memories, never()).save(any(), any(), any(), any(), any());
        fixture.service.approve(fixture.authentication, 99L);
        verify(fixture.memories).save("actor", 7L, "test.lesson", "verified correction", null);
        assertThat(fixture.result().getFailed()).isFalse();
        assertThat(JSON.readTree(fixture.result().getOperationMetadataJson()).path("approvalDecision").stringValue()).isEqualTo("USER_APPROVED");
    }

    @Test
    void deleteAlsoWaitsForApproval() {
        var fixture = fixture("DIRECT", "ASK");
        fixture.call(MemoryAiTools.DELETE_TOOL, "{\"key\":\"test.lesson\",\"version\":0}");
        verify(fixture.memories, never()).delete(any(), any(), anyLong());
        fixture.service.approve(fixture.authentication, 99L);
        verify(fixture.memories).delete("actor", "test.lesson", 0);
        assertThat(fixture.result().getFailed()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void autoPolicyKeepsTheReviewerInControlOfProactiveCapture(boolean allowed) {
        var fixture = fixture("DIRECT", "AUTO");
        when(fixture.worker.review(any())).thenReturn(ReviewToolCallResponse.newBuilder().setAllowed(allowed).setReason("test review").build());
        fixture.call(MemoryAiTools.SAVE_TOOL, SAVE_ARGUMENTS);
        var request = ArgumentCaptor.forClass(ReviewToolCallRequest.class);
        verify(fixture.worker).review(request.capture());
        assertThat(request.getValue().getToolDescription()).contains("proactively", "verified correction");
        if (allowed)
            verify(fixture.memories).save("actor", 7L, "test.lesson", "verified correction", null);
        else
            verify(fixture.memories, never()).save(any(), any(), any(), any(), any());

        assertThat(fixture.result().getFailed()).isEqualTo(!allowed);
    }

    @Test
    void refreshesTheIndexOnTheNextTurn() {
        var fixture = fixture("READ_ONLY", "ASK");
        fixture.service.cancel(fixture.authentication, 8L);
        when(fixture.persistence.start(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AiChatPersistence.StartedTurn(9L, 10L));
        when(fixture.memories.index("actor")).thenReturn(new AiMemoryService.Index(
                List.of(new AiMemoryService.IndexEntry("new.lesson", Instant.now())), false, AiMemoryService.CONTEXT_RULES));
        fixture.service.submit(fixture.authentication, new AiChatService.SubmitRequest(null, "next request", "READ_ONLY", "ASK", "en"));
        assertThat(fixture.sent().getLast().getStart().getInstructions()).contains("new.lesson").doesNotContain("\"test.lesson\"");
    }

    @ParameterizedTest
    @ValueSource(strings = { "READ_ONLY", "PLAN", "DIRECT" })
    void disabledMemoryIsNotPublishedAndForcedToolsAndGroupAreDenied(String mode) {
        var fixture = fixture(mode, "ASK", false);
        assertThat(fixture.sent().getFirst().getStart().getInstructions()).doesNotContain("test.lesson", "Load MEMORY", "MEMORY the authenticated");
        assertThat(fixture.sent().getFirst().getStart().getToolsList()).allSatisfy(tool -> {
            assertThat(tool.getName()).doesNotStartWith("alertify_memory_");
            assertThat(tool.getInputSchema()).doesNotContain("\"MEMORY\"");
        });
        fixture.call("alertify_load_tool_group", "{\"group\":\"MEMORY\"}");
        assertThat(fixture.result().getDiagnosticCode()).isEqualTo("MEMORY_DISABLED");
        assertThat(fixture.result().getAdditionalToolsList()).isEmpty();
        fixture.call(MemoryAiTools.SEARCH_TOOL, "{\"query\":\"\",\"limit\":null,\"cursor\":null}");
        assertThat(fixture.result().getDiagnosticCode()).isEqualTo("MEMORY_DISABLED");
        fixture.call(MemoryAiTools.SAVE_TOOL, SAVE_ARGUMENTS);
        assertThat(fixture.result().getDiagnosticCode()).isEqualTo("MEMORY_DISABLED");
        fixture.call(MemoryAiTools.DELETE_TOOL, "{\"key\":\"test.lesson\",\"version\":0}");
        assertThat(fixture.result().getDiagnosticCode()).isEqualTo("MEMORY_DISABLED");
        verifyNoInteractions(fixture.memories);
    }

    @Test
    void aRunningTurnRejectsAccessWhenDisabledAndReadsTheSameMemoriesWhenEnabledAgain() {
        var fixture = fixture("DIRECT", "ASK");
        fixture.call("alertify_load_tool_group", "{\"group\":\"MEMORY\"}");
        var settings = fixture.worker.settings();
        when(fixture.worker.settings()).thenReturn(settings.toBuilder().setMemoryEnabled(false).build());
        fixture.call(MemoryAiTools.SEARCH_TOOL, "{\"query\":\"lesson\",\"limit\":null,\"cursor\":null}");
        assertThat(fixture.result().getDiagnosticCode()).isEqualTo("MEMORY_DISABLED");
        verify(fixture.memories, never()).search(any(), any(), any(), any());
        when(fixture.worker.settings()).thenReturn(settings);
        fixture.call(MemoryAiTools.SEARCH_TOOL, "{\"query\":\"lesson\",\"limit\":null,\"cursor\":null}");
        assertThat(fixture.result().getOutput()).contains("PRIVATE-MEMORY-CONTENT");
    }

    @ParameterizedTest
    @ValueSource(strings = { MemoryAiTools.SAVE_TOOL, MemoryAiTools.DELETE_TOOL })
    void disablingWhileAskIsPendingPreventsTheApprovedCallback(String name) {
        var fixture = fixture("DIRECT", "ASK");
        fixture.call(name, name.equals(MemoryAiTools.SAVE_TOOL) ? SAVE_ARGUMENTS : "{\"key\":\"test.lesson\",\"version\":0}");
        when(fixture.worker.settings()).thenReturn(AiSettings.newBuilder().setMemoryEnabled(false).build());
        fixture.service.approve(fixture.authentication, 99L);
        assertThat(fixture.result().getDiagnosticCode()).isEqualTo("MEMORY_DISABLED");
        var receipt = JSON.readTree(fixture.result().getOperationMetadataJson());
        assertThat(receipt.path("approvalDecision").stringValue()).isEqualTo("USER_APPROVED");
        assertThat(receipt.path("phase").stringValue()).isEqualTo("PRE_EXECUTION");
        verify(fixture.memories, never()).save(any(), any(), any(), any(), any());
        verify(fixture.memories, never()).delete(any(), any(), anyLong());
    }

    @Test
    void disablingDuringAutoReviewPreventsExecution() {
        var fixture = fixture("DIRECT", "AUTO");
        when(fixture.worker.review(any())).thenAnswer(call -> {
            when(fixture.worker.settings()).thenReturn(AiSettings.newBuilder().setMemoryEnabled(false).build());
            return ReviewToolCallResponse.newBuilder().setAllowed(true).build();
        });
        fixture.call(MemoryAiTools.SAVE_TOOL, SAVE_ARGUMENTS);
        assertThat(fixture.result().getDiagnosticCode()).isEqualTo("MEMORY_DISABLED");
        assertThat(JSON.readTree(fixture.result().getOperationMetadataJson()).path("approvalDecision").stringValue()).isEqualTo("AUTO_APPROVED");
        verify(fixture.memories, never()).save(any(), any(), any(), any(), any());
    }

    @Test
    void failsClosedWhenTheSettingCannotBeVerified() {
        var fixture = fixture("DIRECT", "ASK");
        when(fixture.worker.settings()).thenThrow(new IllegalStateException("private diagnostics"));
        fixture.call(MemoryAiTools.SEARCH_TOOL, "{\"query\":\"\",\"limit\":null,\"cursor\":null}");
        assertThat(fixture.result().getDiagnosticCode()).isEqualTo("MEMORY_SETTINGS_UNAVAILABLE");
        assertThat(fixture.result().getOutput()).doesNotContain("private diagnostics");
        verify(fixture.memories, never()).search(any(), any(), any(), any());
    }

    private Fixture fixture(String mode, String policy) {
        return fixture(mode, policy, true);
    }

    private Fixture fixture(String mode, String policy, boolean memoryEnabled) {
        var worker = mock(AiWorkerClient.class);
        var catalog = mock(AiToolCatalog.class);
        var persistence = mock(AiChatPersistence.class);
        var memories = mock(AiMemoryService.class);
        var logger = mock(ApplicationEventLogger.class);
        var localization = mock(AiLocalizationSourceService.class);
        var authentication = UsernamePasswordAuthenticationToken.authenticated("actor", "unused", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        var facade = new MemoryAiTools(memories, new AiToolExecutor(logger), worker);
        var descriptors = Arrays.stream(ToolCallbacks.from(facade)).map(callback -> {
            var definition = callback.getToolDefinition();
            boolean readOnly = MemoryAiTools.SEARCH_TOOL.equals(definition.name());
            return new AiToolDescriptor(callback, definition.name(), definition.description(), false, DefaultToolCallResultConverter.class,
                    readOnly, MemoryAiTools.DELETE_TOOL.equals(definition.name()), readOnly, false, readOnly ? Set.of() : Set.of(AiRefreshTarget.MEMORIES));
        }).toList();
        when(catalog.descriptorsFor(any(), any())).thenReturn(descriptors);
        when(localization.resolveLocale(any())).thenReturn("en");
        when(worker.state()).thenReturn(ModuleStateResponse.newBuilder().setEnabled(true).setSessionStatus("ACTIVE").build());
        when(worker.settings()).thenReturn(AiSettings.newBuilder().setModel("model").setReasoningEffort("low").setConversationHistorySearchEnabled(false).setMemoryEnabled(memoryEnabled).build());
        when(persistence.start(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(new AiChatPersistence.StartedTurn(7L, 8L));
        when(persistence.approvalIsValid(99L, "actor")).thenReturn(true);
        when(memories.index("actor")).thenReturn(new AiMemoryService.Index(
                List.of(new AiMemoryService.IndexEntry("test.lesson", Instant.now())), false, AiMemoryService.CONTEXT_RULES));
        var memory = new AiMemoryService.Memory("test.lesson", "PRIVATE-MEMORY-CONTENT", 0, Instant.now(), Instant.now(), 7L);
        when(memories.search(any(), any(), any(), any())).thenReturn(new AiMemoryService.SearchResult(List.of(memory), null, AiMemoryService.CONTEXT_RULES));
        when(memories.save(any(), any(), any(), any(), any())).thenReturn(memory);
        when(memories.delete(any(), any(), anyLong())).thenReturn(new AiMemoryService.Deleted("test.lesson", true));
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
                logger, JSON, mock(AiConversationHistory.class), memories);
        service.submit(authentication, new AiChatService.SubmitRequest(null, "fix a reusable problem", mode, policy, "en"));
        return new Fixture(service, worker, persistence, memories, authentication, consumer.getValue(), handle);
    }

    private record Fixture(AiChatService service, AiWorkerClient worker, AiChatPersistence persistence, AiMemoryService memories,
            Authentication authentication, Consumer<ChatWorkerMessage> consumer, AiWorkerClient.ChatHandle handle) {
        void call(String name, String arguments) {
            consumer.accept(ChatWorkerMessage.newBuilder().setToolCall(ChatToolCall.newBuilder().setCallId("call").setName(name).setArguments(arguments)).build());
        }

        List<ChatClientMessage> sent() {
            var sent = ArgumentCaptor.forClass(ChatClientMessage.class);
            verify(handle, atLeastOnce()).send(sent.capture());
            return sent.getAllValues();
        }

        ChatToolResult result() {
            return sent().stream().filter(ChatClientMessage::hasToolResult).toList().getLast().getToolResult();
        }
    }
}
