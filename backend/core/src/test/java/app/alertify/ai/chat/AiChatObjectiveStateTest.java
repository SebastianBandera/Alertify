package app.alertify.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class AiChatObjectiveStateTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void ordinaryQuestionAndSimpleWorkDoNotCreateObjectivesOrResumeExistingWork() {
        ObjectNode question = input("ASSESS", "QUESTION", 0, "ACTIVE");
        assertThat(change(null, question).document()).isNull();
        ObjectNode simple = input("ASSESS", "WORK", 0, "ACTIVE");
        simple.withArray("steps").remove(1);
        assertThat(change(null, simple).participates()).isFalse();
        JsonNode existing = start();
        question.put("expectedRevision", existing.path("revision").asLong());
        assertThat(change(existing, question).document()).isEqualTo(existing);
        assertThat(change(existing, question).participates()).isFalse();
    }

    @Test
    void createsStructuredPendingStepsAndRejectsPrematureCompletionOrRevisionConflicts() {
        JsonNode state = start();
        assertThat(state.path("sourceMessageIds").get(0).asLong()).isEqualTo(10);
        assertThat(state.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(AiChatObjectiveState.canComplete(state)).isFalse();
        assertThatThrownBy(() -> change(state, input("UPDATE", "WORK", 1, "COMPLETED"))).hasMessageContaining("Every objective step");
        assertThatThrownBy(() -> change(state, input("UPDATE", "WORK", 0, "ACTIVE"))).hasMessageContaining("REVISION_CONFLICT");
    }

    @Test
    void successfulFreshDomainReadsVerifyReadTasksButFailedOrInternalCallsDoNot() {
        JsonNode state = start();
        ObjectNode update = input("UPDATE", "WORK", 1, "COMPLETED");
        mark(update, "VERIFIED", 21);
        JsonNode verified = change(state, update, invocation(21, true, "SUCCEEDED", "DOMAIN", 8, 2)).document();
        assertThat(AiChatObjectiveState.canComplete(verified)).isTrue();
        assertThatThrownBy(() -> change(state, update, invocation(21, true, "FAILED", "DOMAIN", 8, 2))).hasMessageContaining("Only successful domain");
        assertThatThrownBy(() -> change(state, update, invocation(21, true, "SUCCEEDED", "INTERNAL", 8, 2))).hasMessageContaining("Only successful domain");
        assertThatThrownBy(() -> change(state, update, invocation(21, true, "SUCCEEDED", "DOMAIN", 7, 2))).hasMessageContaining("from this turn");
    }

    @Test
    void verificationMustFollowTheWriteAndCannotDropPriorWriteEvidence() {
        JsonNode state = start();
        AiToolInvocation write = invocation(21, false, "SUCCEEDED", "DOMAIN", 8, 3);
        AiToolInvocation before = invocation(22, true, "SUCCEEDED", "DOMAIN", 8, 2);
        AiToolInvocation after = invocation(23, true, "SUCCEEDED", "DOMAIN", 8, 4);
        ObjectNode applied = input("UPDATE", "WORK", 1, "ACTIVE");
        mark(applied, "APPLIED", 21);
        JsonNode saved = change(state, applied, write).document();
        ObjectNode verify = input("UPDATE", "WORK", 2, "COMPLETED");
        mark(verify, "VERIFIED", 21, 22);
        assertThatThrownBy(() -> change(saved, verify, write, before)).hasMessageContaining("after that write");
        mark(verify, "VERIFIED", 23);
        assertThatThrownBy(() -> change(saved, verify, after)).hasMessageContaining("Keep prior step evidence");
        mark(verify, "VERIFIED", 21, 23);
        assertThat(change(saved, verify, write, after).document().path("status").asString()).isEqualTo("COMPLETED");
    }

    @Test
    void interruptionPreservesPendingAndAppliedEvidenceAndRequiresCurrentReconciliation() {
        ObjectNode applied = input("UPDATE", "WORK", 1, "ACTIVE");
        mark(applied, "APPLIED", 21);
        AiToolInvocation write = invocation(21, false, "SUCCEEDED", "DOMAIN", 8, 3);
        JsonNode saved = change(start(), applied, write).document();
        JsonNode interrupted = AiChatObjectiveState.interrupted(saved, "Backend restarted");
        assertThat(interrupted.path("steps")).isEqualTo(saved.path("steps"));
        assertThat(interrupted.path("needsReconciliation").asBoolean()).isTrue();
        ObjectNode resume = input("ASSESS", "RESUME", interrupted.path("revision").asLong(), "ACTIVE");
        JsonNode resumed = change(interrupted, resume, write).document();
        assertThat(resumed.path("needsReconciliation").asBoolean()).isTrue();
        ObjectNode reconcile = input("UPDATE", "WORK", resumed.path("revision").asLong(), "COMPLETED");
        mark(reconcile, "VERIFIED", 21, 22);
        JsonNode complete = change(resumed, reconcile, write, invocation(22, true, "SUCCEEDED", "DOMAIN", 8, 4)).document();
        assertThat(complete.path("needsReconciliation").asBoolean()).isFalse();
    }

    @Test
    void revisionPreservesIdentityAndUserSourcesWhileProgressPreservesCanonicalScope() {
        JsonNode previous = start();
        ObjectNode revise = input("ASSESS", "REVISE", 1, "ACTIVE");
        revise.put("summary", "Revised scope");
        JsonNode next = AiChatObjectiveState.change(previous, revise, 11, 8, List.of(), List.of()).document();
        assertThat(next.path("id")).isEqualTo(previous.path("id"));
        assertThat(next.path("sourceMessageIds").valueStream().map(JsonNode::asLong).toList()).containsExactly(10L, 11L);
        ObjectNode silent = input("UPDATE", "WORK", 1, "ACTIVE");
        silent.put("summary", "Cosmetically reformulated summary");
        ((ObjectNode) silent.path("steps").get(0)).put("title", "Different task");
        JsonNode unchangedScope = change(previous, silent).document();
        assertThat(unchangedScope.path("summary")).isEqualTo(previous.path("summary"));
        assertThat(unchangedScope.path("steps")).isEqualTo(previous.path("steps"));
        assertThat(unchangedScope.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(AiChatObjectiveState.canComplete(unchangedScope)).isFalse();
        silent.put("status", "COMPLETED");
        assertThatThrownBy(() -> change(previous, silent)).hasMessageContaining("Every objective step");
        silent.put("status", "ACTIVE");
        silent.withArray("steps").remove(1);
        assertThatThrownBy(() -> change(previous, silent)).hasMessageContaining("Changing the objective steps");
        ((ObjectNode) silent.path("steps").get(0)).put("id", "different-step-id");
        assertThatThrownBy(() -> change(previous, silent)).hasMessageContaining("Changing the objective steps");
    }

    @Test
    void initialAmbiguityAndCancellationHaveExplicitStatesAndSanitizedText() {
        ObjectNode input = input("ASSESS", "WORK", 0, "NEEDS_DECISION");
        input.put("summary", "Bearer canary-token").put("reason", "password=canary-password");
        JsonNode state = change(null, input).document();
        assertThat(state.path("status").asString()).isEqualTo("NEEDS_DECISION");
        assertThat(state.toString()).doesNotContain("canary-token", "canary-password");
        ObjectNode cancel = input("ASSESS", "CANCEL", 1, "ACTIVE");
        JsonNode cancelled = change(state, cancel).document();
        assertThat(cancelled.path("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.path("steps")).isEqualTo(state.path("steps"));
    }

    @Test
    void onlyAnExactMatchedBackendPatchReadbackCanStandInForADomainRead() {
        JsonNode state = start();
        AiToolInvocation patch = invocation(21, false, "SUCCEEDED", "DOMAIN", 8, 3);
        when(patch.getToolName()).thenReturn("alertify_configuration_patch");
        when(patch.getResult()).thenReturn("{\"id\":45,\"version\":2,\"name\":\"Expected\"}");
        AiToolInvocation read = invocation(22, true, "SUCCEEDED", "INTERNAL", 8, 4);
        when(read.getCallId()).thenReturn("patch-read:21");
        when(read.getToolName()).thenReturn("alertify_configuration_get");
        when(read.getResult()).thenReturn("{\"id\":45,\"version\":2,\"name\":\"Expected\"}");
        ObjectNode verify = input("UPDATE", "WORK", 1, "COMPLETED");
        mark(verify, "VERIFIED", 21, 22);
        assertThat(change(state, verify, patch, read).document().path("status").asString()).isEqualTo("COMPLETED");
        for (String mismatch : List.of("{\"id\":45,\"version\":2,\"name\":\"Different\"}", "{\"id\":45,\"version\":3,\"name\":\"Expected\"}", "{\"id\":46,\"version\":2,\"name\":\"Expected\"}")) {
            when(read.getResult()).thenReturn(mismatch);
            assertThatThrownBy(() -> change(state, verify, patch, read)).hasMessageContaining("Only successful domain");
        }
        when(read.getResult()).thenReturn("{\"id\":45,\"version\":2,\"name\":\"Expected\"}");
        when(read.getCallId()).thenReturn("patch-read:999");
        assertThatThrownBy(() -> change(state, verify, patch, read)).hasMessageContaining("Only successful domain");
        when(read.getCallId()).thenReturn("patch-read:21");
        when(read.getToolName()).thenReturn("alertify_pipe_get");
        assertThatThrownBy(() -> change(state, verify, patch, read)).hasMessageContaining("Only successful domain");
    }

    private JsonNode start() { return change(null, input("ASSESS", "WORK", 0, "ACTIVE")).document(); }
    private AiChatObjectiveState.Change change(JsonNode state, JsonNode input, AiToolInvocation... evidence) { return AiChatObjectiveState.change(state, input, 10, 8, List.of(evidence), List.of()); }

    static ObjectNode input(String operation, String kind, long revision, String status) {
        ObjectNode node = JSON.createObjectNode().put("operation", operation).put("kind", kind).put("expectedRevision", revision).put("status", status).put("summary", "Controlled task").put("reason", "Decision or blockage detail");
        var steps = node.putArray("steps");
        for (int index = 1; index <= 2; index++) steps.add(JSON.createObjectNode().put("id", "s" + index).put("title", "Step " + index).put("status", "PENDING").set("evidenceInvocationIds", JSON.createArrayNode()));
        return node;
    }

    private static void mark(ObjectNode input, String status, long... ids) {
        for (JsonNode step : input.path("steps")) {
            ((ObjectNode) step).put("status", status);
            var evidence = ((ObjectNode) step).putArray("evidenceInvocationIds");
            for (long id : ids) evidence.add(id);
        }
    }

    private static AiToolInvocation invocation(long id, boolean readOnly, String status, String kind, long turnId, long finishedSeconds) {
        var value = mock(AiToolInvocation.class);
        var turn = mock(AiTurn.class);
        when(turn.getId()).thenReturn(turnId);
        when(value.getId()).thenReturn(id);
        when(value.getTurn()).thenReturn(turn);
        when(value.getStatus()).thenReturn(status);
        when(value.getKind()).thenReturn(kind);
        when(value.isReadOnly()).thenReturn(readOnly);
        when(value.getFinishedAt()).thenReturn(Instant.EPOCH.plusSeconds(finishedSeconds));
        return value;
    }
}
