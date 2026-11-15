package app.alertify.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import app.alertify.ai.AiToolCatalog;
import app.alertify.ai.AiToolDescriptor;
import app.alertify.ai.localization.AiLocalizationSourceService;
import app.alertify.ai.memory.AiMemoryService;
import app.alertify.ai.tools.MemoryAiTools;
import app.alertify.codex.AiWorkerClient;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.realtime.AdminEventPublisher;
import app.alertify.worker.ai.grpc.AiSettings;
import app.alertify.worker.ai.grpc.ChatClientMessage;
import app.alertify.worker.ai.grpc.ChatCompleted;
import app.alertify.worker.ai.grpc.ChatCompletionCheck;
import app.alertify.worker.ai.grpc.ChatRequestPermitCheck;
import app.alertify.worker.ai.grpc.ChatToolCall;
import app.alertify.worker.ai.grpc.ChatWorkerMessage;
import app.alertify.worker.ai.grpc.ModuleStateResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class AiChatObjectiveServiceTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void unstructuredCompletionCannotCloseATurnAndPendingTextIsOnlyProgress() {
        Fixture unassessed = new Fixture("ASK", null);
        unassessed.emit(ChatWorkerMessage.newBuilder().setCompleted(ChatCompleted.getDefaultInstance()).build());
        verify(unassessed.persistence).failed(8L, "AI_OBJECTIVE_INCOMPLETE", "The worker attempted to close a turn before its structured objective was resolved");
        verify(unassessed.persistence, never()).completed(anyLong());

        Fixture pending = new Fixture("ASK", null);
        pending.assess("WORK", "ACTIVE", false);
        pending.checkCompletion();
        assertThat(pending.last().getCompletionDecision().getFinish()).isFalse();
        assertThat(pending.state.get().path("status").asString()).isEqualTo("ACTIVE");
        verify(pending.persistence, never()).completed(anyLong());
        pending.close();
    }

    @Test
    void verifiedObjectivePassesTheBackendCompletionGate() {
        Fixture fixture = new Fixture("ASK", null);
        fixture.assess("WORK", "ACTIVE", false);
        fixture.call("fixture_read", "{}");
        long evidence = fixture.calls.getLast().getId();
        ObjectNode complete = AiChatObjectiveStateTest.input("UPDATE", "WORK", fixture.revision(), "COMPLETED");
        for (JsonNode step : complete.path("steps")) ((ObjectNode) step).put("status", "VERIFIED").putArray("evidenceInvocationIds").add(evidence);
        fixture.objective(complete);
        assertThat(fixture.state.get().path("status").asString()).isEqualTo("COMPLETED");
        fixture.checkCompletion();
        assertThat(fixture.last().getCompletionDecision().getFinish()).isTrue();
        fixture.emit(ChatWorkerMessage.newBuilder().setCompleted(ChatCompleted.getDefaultInstance()).build());
        verify(fixture.persistence).completed(8L);
    }

    @Test
    void failedObjectiveUpdateReturnsCanonicalStateAndRevisionWithoutEchoingRejectedInput() {
        Fixture fixture = new Fixture("ASK", null);
        fixture.assess("WORK", "ACTIVE", false);
        JsonNode canonical = fixture.state.get();
        ObjectNode stale = AiChatObjectiveStateTest.input("UPDATE", "WORK", 0, "ACTIVE");
        stale.put("summary", "Bearer REJECTED-INPUT-CANARY").put("reason", "password=REJECTED-PASSWORD-CANARY");
        fixture.objective(stale);
        var failed = fixture.last().getToolResult();
        assertThat(failed.getFailed()).isTrue();
        assertThat(failed.getDiagnosticCode()).isEqualTo("AI_OBJECTIVE_REVISION_CONFLICT");
        JsonNode receipt = JSON.readTree(failed.getOutput());
        assertThat(receipt.path("revision").asLong()).isEqualTo(1);
        assertThat(receipt.path("objective")).isEqualTo(JSON.readTree(JSON.writeValueAsString(canonical)));
        assertThat(receipt.path("correction").asString()).contains("Retry UPDATE", "returned revision");
        assertThat(receipt.path("gate").path("assessed").asBoolean()).isTrue();
        assertThat(receipt.path("gate").path("canComplete").asBoolean()).isFalse();
        assertThat(failed.getOutput()).doesNotContain("REJECTED-INPUT-CANARY", "REJECTED-PASSWORD-CANARY");
        ObjectNode retry = AiChatObjectiveStateTest.input("UPDATE", "WORK", receipt.path("revision").asLong(), "ACTIVE");
        retry.put("summary", "Reformulated without changing the stored scope");
        ((ObjectNode) retry.path("steps").get(0)).put("title", "A differently worded first step");
        fixture.objective(retry);
        assertThat(fixture.last().getToolResult().getFailed()).isFalse();
        assertThat(fixture.state.get().path("summary")).isEqualTo(canonical.path("summary"));
        assertThat(fixture.state.get().path("steps")).isEqualTo(canonical.path("steps"));
        assertThat(fixture.state.get().path("status").asString()).isEqualTo("ACTIVE");
        fixture.objective(AiChatObjectiveStateTest.input("ASSESS", "WORK", fixture.revision(), "ACTIVE"));
        assertThat(fixture.last().getToolResult().getDiagnosticCode()).isEqualTo("AI_OBJECTIVE_ALREADY_ASSESSED");
        assertThat(JSON.readTree(fixture.last().getToolResult().getOutput()).path("correction").asString()).contains("Use UPDATE");
        fixture.close();
    }

    @Test
    void questionDoesNotResumeOrModifyPriorObjectiveButKeepsScopedMemorySaveApproval() {
        JsonNode old = AiChatObjectiveState.change(null, AiChatObjectiveStateTest.input("ASSESS", "WORK", 0, "ACTIVE"), 5, 7, List.of(), List.of()).document();
        Fixture fixture = new Fixture("ASK", old);
        fixture.assess("QUESTION", "ACTIVE", false);
        fixture.call("fixture_write", "{}");
        verify(fixture.write, never()).call(any());
        fixture.objective(AiChatObjectiveStateTest.input("UPDATE", "WORK", 1, "BLOCKED"));
        assertThat(fixture.last().getToolResult().getFailed()).isTrue();
        assertThat(fixture.state.get()).isEqualTo(old);
        fixture.call(MemoryAiTools.DELETE_TOOL, "{}");
        verify(fixture.memoryDelete, never()).call(any());
        fixture.call(MemoryAiTools.SAVE_TOOL, "{}");
        verify(fixture.memorySave, never()).call(any());
        long approval = fixture.calls.getLast().getId();
        fixture.service.approve(fixture.authentication, approval);
        verify(fixture.memorySave).call("{}");
        assertThat(fixture.state.get()).isEqualTo(old);
        fixture.checkCompletion();
        assertThat(fixture.last().getCompletionDecision().getFinish()).isTrue();
        fixture.close();
    }

    @Test
    void simpleWorkRemainsSubjectToAskAndAmbiguousSimpleWorkCannotWrite() {
        Fixture simple = new Fixture("ASK", null);
        simple.assess("WORK", "ACTIVE", true);
        assertThat(simple.state.get()).isNull();
        simple.call("fixture_write", "{}");
        verify(simple.write, never()).call(any());
        simple.service.approve(simple.authentication, simple.calls.getLast().getId());
        verify(simple.write).call("{}");
        simple.close();

        Fixture uncertain = new Fixture("AUTO", null);
        uncertain.assess("WORK", "NEEDS_DECISION", true);
        uncertain.call("fixture_write", "{}");
        verify(uncertain.write, never()).call(any());
        verify(uncertain.worker, never()).review(any());
        uncertain.close();
    }

    @Test
    void interruptedObjectiveRequiresFreshReadReconciliationBeforeAnotherWriteAndDropsLateUpdates() {
        JsonNode old = AiChatObjectiveState.change(null, AiChatObjectiveStateTest.input("ASSESS", "WORK", 0, "ACTIVE"), 5, 7, List.of(), List.of()).document();
        Fixture fixture = new Fixture("ASK", AiChatObjectiveState.interrupted(old, "Restart"));
        fixture.assess("RESUME", "ACTIVE", false);
        fixture.call("fixture_write", "{}");
        verify(fixture.write, never()).call(any());
        fixture.call("fixture_read", "{}");
        long readId = fixture.calls.getLast().getId();
        ObjectNode reconcile = AiChatObjectiveStateTest.input("UPDATE", "WORK", fixture.revision(), "ACTIVE");
        for (JsonNode step : reconcile.path("steps")) ((ObjectNode) step).putArray("evidenceInvocationIds").add(readId);
        fixture.objective(reconcile);
        assertThat(fixture.state.get().path("needsReconciliation").asBoolean()).isFalse();
        fixture.call("fixture_write", "{}");
        fixture.service.approve(fixture.authentication, fixture.calls.getLast().getId());
        verify(fixture.write).call("{}");
        fixture.close();
        JsonNode stopped = fixture.state.get();
        fixture.objective(AiChatObjectiveStateTest.input("UPDATE", "WORK", fixture.revision(), "COMPLETED"));
        assertThat(fixture.state.get()).isSameAs(stopped);
    }

    @Test
    void providerPermitLimitIsCentralAndDoesNotResetAtRequestBlockBoundaries() {
        Fixture fixture = new Fixture("ASK", null);
        fixture.assess("QUESTION", "ACTIVE", false);
        for (int index = 0; index < 512; index++) {
            fixture.emit(ChatWorkerMessage.newBuilder().setRequestPermitCheck(ChatRequestPermitCheck.newBuilder().setRequestId("request-" + index)).build());
            assertThat(fixture.last().getRequestPermitDecision().getAllowed()).isTrue();
        }
        fixture.emit(ChatWorkerMessage.newBuilder().setRequestPermitCheck(ChatRequestPermitCheck.newBuilder().setRequestId("request-513")).build());
        assertThat(fixture.last().getRequestPermitDecision().getDiagnosticCode()).isEqualTo("AI_STEP_LIMIT");
        verify(fixture.persistence).failed(8L, "AI_STEP_LIMIT", "The chat turn exhausted its request or time budget; pending objective work remains saved");
    }

    @Test
    void anAutomaticReviewReturningAfterCancellationCannotResurrectTheTurnOrExecute() throws Exception {
        Fixture fixture = new Fixture("AUTO", null);
        fixture.assess("WORK", "ACTIVE", true);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(fixture.worker.review(any())).thenAnswer(call -> {
            entered.countDown();
            assertThat(release.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            return app.alertify.worker.ai.grpc.ReviewToolCallResponse.newBuilder().setAllowed(true).build();
        });
        Thread review = Thread.ofVirtual().start(() -> fixture.call("fixture_write", "{}"));
        assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        fixture.close();
        release.countDown();
        review.join(5000);
        assertThat(review.isAlive()).isFalse();
        verify(fixture.persistence, never()).toolApproved(anyLong(), any(), any(), any(), any());
        verify(fixture.write, never()).call(any());
    }

    @Test
    void aGuardDeniedAskWriteNeverTransitionsTheTurnToWaitingApproval() {
        JsonNode previous = AiChatObjectiveState.change(null, AiChatObjectiveStateTest.input("ASSESS", "WORK", 0, "ACTIVE"), 5, 7, List.of(), List.of()).document();
        Fixture fixture = new Fixture("ASK", AiChatObjectiveState.interrupted(previous, "Restart"));
        fixture.assess("RESUME", "ACTIVE", false);
        fixture.call("fixture_write", "{}");
        assertThat(fixture.last().getToolResult().getDiagnosticCode()).isEqualTo("AI_OBJECTIVE_RECONCILIATION_REQUIRED");
        assertThat(fixture.turn.getStatus()).isEqualTo("RUNNING");
        assertThat(fixture.calls.getLast().getStatus()).isEqualTo("FAILED");
        assertThat((java.util.Map<?, ?>) ReflectionTestUtils.getField(fixture.service, "pendingApprovals")).isEmpty();
        verify(fixture.write, never()).call(any());
        fixture.close();
    }

    @Test
    void aCallFinishingItsExternalPrecheckAfterCancellationCannotCreateWaitingApproval() throws Exception {
        Fixture fixture = new Fixture("ASK", null);
        fixture.assess("WORK", "ACTIVE", true);
        AiSettings settings = fixture.worker.settings();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(fixture.worker.settings()).thenAnswer(call -> {
            entered.countDown();
            assertThat(release.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            return settings;
        });
        int invocationsBefore = fixture.calls.size();
        Thread pending = Thread.ofVirtual().start(() -> fixture.call(MemoryAiTools.SAVE_TOOL, "{}"));
        assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        fixture.close();
        release.countDown();
        pending.join(5000);
        assertThat(pending.isAlive()).isFalse();
        assertThat(fixture.calls).hasSize(invocationsBefore);
        assertThat(fixture.turn.getStatus()).isEqualTo("CANCELLED");
        assertThat((java.util.Map<?, ?>) ReflectionTestUtils.getField(fixture.service, "pendingApprovals")).isEmpty();
        verify(fixture.memorySave, never()).call(any());
    }

    private static final class Fixture {
        final AiWorkerClient worker = mock(AiWorkerClient.class);
        final AiChatPersistence persistence = mock(AiChatPersistence.class);
        final Authentication authentication = mock(Authentication.class);
        final AiWorkerClient.ChatHandle handle = mock(AiWorkerClient.ChatHandle.class);
        final ToolCallback write = callback("fixture_write");
        final ToolCallback read = callback("fixture_read");
        final ToolCallback memorySave = callback(MemoryAiTools.SAVE_TOOL);
        final ToolCallback memoryDelete = callback(MemoryAiTools.DELETE_TOOL);
        final AtomicReference<JsonNode> state;
        final List<AiToolInvocation> calls = new ArrayList<>();
        final AiTurn turn;
        final AiChatService service;
        Consumer<ChatWorkerMessage> consumer;

        Fixture(String approval, JsonNode objective) {
            state = new AtomicReference<>(objective);
            when(authentication.getName()).thenReturn("subject");
            when(worker.state()).thenReturn(ModuleStateResponse.newBuilder().setEnabled(true).setSessionStatus("ACTIVE").build());
            when(worker.settings()).thenReturn(AiSettings.newBuilder().setModel("model").setReasoningEffort("high").setMemoryEnabled(true).build());
            when(worker.chat(any(), any(), any())).thenAnswer(call -> { consumer = call.getArgument(0); return handle; });
            var catalog = mock(AiToolCatalog.class);
            List<AiToolDescriptor> descriptors = List.of(descriptor(write, false), descriptor(read, true), descriptor(memorySave, false), descriptor(memoryDelete, false));
            when(catalog.descriptorsFor(any(), any())).thenReturn(descriptors);
            var localization = mock(AiLocalizationSourceService.class);
            when(localization.resolveLocale(any())).thenReturn("en");
            when(persistence.start(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(new AiChatPersistence.StartedTurn(7L, 8L));
            var conversation = new AiConversation("subject", "user", "task", "DIRECT", approval);
            ReflectionTestUtils.setField(conversation, "id", 7L);
            turn = new AiTurn(conversation, "model", "high");
            ReflectionTestUtils.setField(turn, "id", 8L);
            var user = new AiMessage(conversation, turn, "USER", "Controlled task");
            ReflectionTestUtils.setField(user, "id", 10L);
            when(persistence.history(7L)).thenReturn(List.of(user));
            when(persistence.objective("subject", 7L)).thenAnswer(call -> state.get());
            when(persistence.updateObjective(any(), anyLong(), anyLong(), any())).thenAnswer(call -> {
                AiChatObjectiveState.Change change = AiChatObjectiveState.change(state.get(), call.getArgument(3), 10, 8, calls, List.of());
                if (change.changed()) state.set(change.document());
                return change;
            });
            when(persistence.tool(any(), anyLong(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(), any(), anyBoolean())).thenAnswer(call -> {
                if (call.<Boolean>getArgument(13)) turn.waitingApproval();

                var invocation = new AiToolInvocation(turn, call.getArgument(1), call.getArgument(2), call.getArgument(3), call.getArgument(4), call.getArgument(5), call.getArgument(6), call.getArgument(7), call.getArgument(8), call.getArgument(9), call.getArgument(10), call.getArgument(11), call.getArgument(12), call.getArgument(13));
                ReflectionTestUtils.setField(invocation, "id", (long) calls.size() + 1);
                calls.add(invocation);
                return invocation;
            });
            when(persistence.approvalIsValid(anyLong(), any())).thenReturn(true);
            doAnswer(call -> { calls.stream().filter(value -> value.getId().equals(call.<Long>getArgument(0))).findFirst().orElseThrow().succeed(call.getArgument(1)); return null; }).when(persistence).toolSucceeded(anyLong(), any());
            doAnswer(call -> { calls.stream().filter(value -> value.getId().equals(call.<Long>getArgument(0))).findFirst().orElseThrow().fail(call.getArgument(1), call.getArgument(2)); return null; }).when(persistence).toolFailed(anyLong(), any(), any());
            doAnswer(call -> { turn.cancel(); return null; }).when(persistence).cancelled(8L);
            doAnswer(call -> { turn.running(); return null; }).when(persistence).toolApproved(anyLong(), any(), any(), any(), any());
            service = new AiChatService(worker, catalog, persistence, mock(AiConversationRepository.class), mock(AiTurnRepository.class), mock(AiMessageRepository.class), mock(AiToolInvocationRepository.class), localization, mock(AdminEventPublisher.class), mock(ApplicationEventLogger.class), JSON, mock(AiConversationHistory.class), mock(AiMemoryService.class), mock(AiPatchReadback.class));
            service.submit(authentication, new AiChatService.SubmitRequest(null, "Controlled task", "DIRECT", approval, "en", "high"));
        }

        long revision() { return AiChatObjectiveState.revision(state.get()); }
        void assess(String kind, String status, boolean simple) {
            ObjectNode input = AiChatObjectiveStateTest.input("ASSESS", kind, revision(), status);
            if (simple) input.withArray("steps").remove(1);
            objective(input);
            assertThat(last().getToolResult().getFailed()).isFalse();
        }
        void objective(JsonNode value) { call(AiChatObjectiveState.TOOL, JSON.writeValueAsString(value)); }
        void call(String name, String arguments) { emit(ChatWorkerMessage.newBuilder().setToolCall(ChatToolCall.newBuilder().setCallId("call-" + calls.size()).setName(name).setArguments(arguments)).build()); }
        void emit(ChatWorkerMessage value) { consumer.accept(value); }
        void checkCompletion() { emit(ChatWorkerMessage.newBuilder().setCompletionCheck(ChatCompletionCheck.newBuilder().setRequestId("check").setCandidateText("Progress")).build()); }
        void close() { service.cancel(authentication, 8L); }
        ChatClientMessage last() {
            var captor = ArgumentCaptor.forClass(ChatClientMessage.class);
            verify(handle, org.mockito.Mockito.atLeastOnce()).send(captor.capture());
            return captor.getAllValues().getLast();
        }
    }

    private static ToolCallback callback(String name) {
        ToolCallback value = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        when(definition.description()).thenReturn(name);
        when(definition.inputSchema()).thenReturn("{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false}");
        when(value.getToolDefinition()).thenReturn(definition);
        when(value.getToolMetadata()).thenReturn(ToolMetadata.builder().build());
        when(value.call(any())).thenReturn("{}");
        return value;
    }

    private static AiToolDescriptor descriptor(ToolCallback callback, boolean readOnly) { return new AiToolDescriptor(callback, callback.getToolDefinition().name(), callback.getToolDefinition().description(), false, DefaultToolCallResultConverter.class, readOnly, false, false, false, Set.of()); }
}
