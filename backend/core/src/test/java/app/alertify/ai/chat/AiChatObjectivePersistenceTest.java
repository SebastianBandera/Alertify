package app.alertify.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class AiChatObjectivePersistenceTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final AiConversationRepository conversations = mock(AiConversationRepository.class);
    private final AiTurnRepository turns = mock(AiTurnRepository.class);
    private final AiMessageRepository messages = mock(AiMessageRepository.class);
    private final AiToolInvocationRepository invocations = mock(AiToolInvocationRepository.class);
    private final AiChatPersistence persistence = new AiChatPersistence(conversations, turns, messages, invocations);

    @Test
    void restartAfterCommittedWriteBeforeMarkingStepPreservesPendingAndRequiresReconciliation() {
        AiConversation conversation = conversation(7);
        AiTurn turn = turn(conversation, 8);
        when(messages.findAllByConversation_IdOrderById(7L)).thenReturn(List.of(user(conversation, turn, 10)));
        when(turns.findAllByStatusIn(any())).thenReturn(List.of(turn));
        when(invocations.findAllByTurn_IdAndStatusIn(any(), any())).thenReturn(List.of());
        var committed = new AiToolInvocation(turn, 1, "write", "fixture_write", "{}", "DOMAIN", true, true, false, false, false, false, "", false);
        committed.succeed("{}");
        persistence.recoverInterruptedTurns();
        var objective = AiChatObjectiveState.read(conversation.getObjectiveDocument());
        assertThat(turn.getErrorCode()).isEqualTo("AI_BACKEND_RESTARTED");
        assertThat(objective.path("status").asString()).isEqualTo("INTERRUPTED");
        assertThat(objective.path("needsReconciliation").asBoolean()).isTrue();
        assertThat(objective.path("steps").get(0).path("status").asString()).isEqualTo("PENDING");
        assertThat(committed.getStatus()).isEqualTo("SUCCEEDED");
    }

    @Test
    void cancellingOrFailingAnUnrelatedQuestionDoesNotChangeThePriorObjective() {
        AiConversation conversation = conversation(7);
        AiTurn original = turn(conversation, 8);
        AiTurn question = turn(conversation, 9);
        when(turns.findById(9L)).thenReturn(Optional.of(question));
        when(messages.findAllByConversation_IdOrderById(7L)).thenReturn(List.of(user(conversation, original, 10), user(conversation, question, 11)));
        when(invocations.findAllByTurn_IdAndStatusIn(any(), any())).thenReturn(List.of());
        String before = conversation.getObjectiveDocument();
        persistence.failed(9L, "TEST_FAILURE", "Controlled failure");
        assertThat(conversation.getObjectiveDocument()).isEqualTo(before);
        persistence.cancelled(9L);
        assertThat(conversation.getObjectiveDocument()).isEqualTo(before);
    }

    @Test
    void evidenceFromAnotherConversationCannotVerifyAnObjectiveEvenWithTheSameOwner() {
        AiConversation conversation = conversation(7);
        AiTurn current = turn(conversation, 8);
        when(conversations.findLocked(7L, "subject")).thenReturn(Optional.of(conversation));
        when(turns.findById(8L)).thenReturn(Optional.of(current));
        when(messages.findAllByConversation_IdOrderById(7L)).thenReturn(List.of(user(conversation, current, 10)));
        AiTurn foreign = turn(conversation(20), 6);
        var invocation = new AiToolInvocation(foreign, 1, "read", "fixture_read", "{}", "DOMAIN", true, true, true, false, true, false, "", false);
        invocation.succeed("{}");
        ReflectionTestUtils.setField(invocation, "id", 21L);
        when(invocations.findByIdAndTurn_Conversation_UserSubject(21L, "subject")).thenReturn(Optional.of(invocation));
        ObjectNode update = AiChatObjectiveStateTest.input("UPDATE", "WORK", 1, "COMPLETED");
        for (var step : update.path("steps")) ((ObjectNode) step).put("status", "VERIFIED").putArray("evidenceInvocationIds").add(21);
        String before = conversation.getObjectiveDocument();
        assertThatThrownBy(() -> persistence.updateObjective("subject", 7L, 8L, update)).hasMessageContaining("outside this conversation");
        assertThat(conversation.getObjectiveDocument()).isEqualTo(before);
    }

    private static AiConversation conversation(long id) {
        var value = new AiConversation("subject", "user", "Controlled task", "DIRECT", "ASK");
        ReflectionTestUtils.setField(value, "id", id);
        value.objectiveDocument(JSON.writeValueAsString(AiChatObjectiveState.change(null, AiChatObjectiveStateTest.input("ASSESS", "WORK", 0, "ACTIVE"), 10, 8, List.of(), List.of()).document()));
        return value;
    }
    private static AiTurn turn(AiConversation conversation, long id) {
        var value = new AiTurn(conversation, "model", "high");
        ReflectionTestUtils.setField(value, "id", id);
        return value;
    }
    private static AiMessage user(AiConversation conversation, AiTurn turn, long id) {
        var value = new AiMessage(conversation, turn, "USER", "Controlled task");
        ReflectionTestUtils.setField(value, "id", id);
        return value;
    }
}
