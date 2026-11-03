package app.alertify.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;

class AiChatPersistenceTest {

    @Test
    void automaticRejectionPersistsDecisionIdentityDateAndReasonTogether() {
        var invocations = mock(AiToolInvocationRepository.class);
        var turn = mock(AiTurn.class);
        var invocation = new AiToolInvocation(turn, 1, "call", "write", "{}", "DOMAIN", true, true, false, false, false, false, "", false);
        when(invocations.findById(1L)).thenReturn(Optional.of(invocation));
        var persistence = new AiChatPersistence(mock(AiConversationRepository.class), mock(AiTurnRepository.class), mock(AiMessageRepository.class), invocations);
        persistence.toolReviewRejected(1L, "Outside scope", "review-model");
        assertThat(invocation.getStatus()).isEqualTo("REJECTED");
        assertThat(invocation.getApprovalDecision()).isEqualTo("REJECTED");
        assertThat(invocation.getApprovalActorType()).isEqualTo("AUTOMATIC_REVIEWER");
        assertThat(invocation.getApprovalActor()).isEqualTo("review-model");
        assertThat(invocation.getApprovalDecidedAt()).isNotNull();
        assertThat(invocation.getReviewReason()).isEqualTo("Outside scope");
    }

    @Test
    void approvalPersistsAuthenticatedUserAndRemainsDistinctFromExecutionFailure() {
        var invocations = mock(AiToolInvocationRepository.class);
        var turn = mock(AiTurn.class);
        var invocation = new AiToolInvocation(turn, 1, "call", "write", "{}", "DOMAIN", true, true, false, false, false, false, "", true);
        when(invocations.findById(1L)).thenReturn(Optional.of(invocation));
        var persistence = new AiChatPersistence(mock(AiConversationRepository.class), mock(AiTurnRepository.class), mock(AiMessageRepository.class), invocations);
        persistence.toolApproved(1L, "Approved by the user", "USER", "tester", "subject-1");
        persistence.toolFailed(1L, "safe failure", "TEST_FAILURE");
        assertThat(invocation.getStatus()).isEqualTo("FAILED");
        assertThat(invocation.getApprovalDecision()).isEqualTo("APPROVED");
        assertThat(invocation.getApprovalActor()).isEqualTo("tester");
        assertThat(invocation.getApprovalActorSubject()).isEqualTo("subject-1");
    }

    @Test
    void rejectingAnApprovalPersistsTheSystemMessageAndCancelsTheTurnAtomically() {
        AiConversationRepository conversations = mock(AiConversationRepository.class);
        AiTurnRepository turns = mock(AiTurnRepository.class);
        AiMessageRepository messages = mock(AiMessageRepository.class);
        AiToolInvocationRepository invocations = mock(AiToolInvocationRepository.class);
        AiToolInvocation invocation = mock(AiToolInvocation.class);
        AiTurn turn = mock(AiTurn.class);
        AiConversation conversation = mock(AiConversation.class);
        when(invocations.findById(12L)).thenReturn(Optional.of(invocation));
        when(invocation.getTurn()).thenReturn(turn);
        when(turn.getId()).thenReturn(8L);
        when(turn.getConversation()).thenReturn(conversation);
        when(invocations.findAllByTurn_IdAndStatusIn(8L, List.of("PENDING_APPROVAL", "RUNNING")))
                .thenReturn(List.of());
        AiChatPersistence persistence = new AiChatPersistence(conversations, turns, messages, invocations);

        persistence.rejectedByUser(12L, "El usuario rechazó la acción solicitada **alertify_configuration_create**.", "tester", "subject-1");

        verify(invocation).reject("Rejected by the user", "USER_REJECTED");
        verify(invocation).approvalDecision("REJECTED", "USER", "tester", "subject-1");
        verify(turn).rejected();
        verify(turn).cancel();
        verify(conversation).touch();
        ArgumentCaptor<AiMessage> message = ArgumentCaptor.forClass(AiMessage.class);
        verify(messages).save(message.capture());
        assertThat(message.getValue().getRole()).isEqualTo("SYSTEM");
        assertThat(message.getValue().getContent()).isEqualTo("El usuario rechazó la acción solicitada **alertify_configuration_create**.");
    }

    @Test
    void cancellingATurnClosesEveryActiveToolInvocation() {
        AiConversationRepository conversations = mock(AiConversationRepository.class);
        AiTurnRepository turns = mock(AiTurnRepository.class);
        AiMessageRepository messages = mock(AiMessageRepository.class);
        AiToolInvocationRepository invocations = mock(AiToolInvocationRepository.class);
        AiToolInvocation running = mock(AiToolInvocation.class);
        AiToolInvocation pending = mock(AiToolInvocation.class);
        AiTurn turn = mock(AiTurn.class);
        when(invocations.findAllByTurn_IdAndStatusIn(9L, List.of("PENDING_APPROVAL", "RUNNING")))
                .thenReturn(List.of(running, pending));
        when(turns.findById(9L)).thenReturn(Optional.of(turn));
        AiChatPersistence persistence = new AiChatPersistence(conversations, turns, messages, invocations);

        persistence.cancelled(9L);

        verify(running).cancel("TURN_CANCELLED");
        verify(pending).cancel("TURN_CANCELLED");
        verify(turn).cancel();
    }

    @Test
    void sessionChangesPersistTheFailureAndInterruptActiveTools() {
        AiConversationRepository conversations = mock(AiConversationRepository.class);
        AiTurnRepository turns = mock(AiTurnRepository.class);
        AiMessageRepository messages = mock(AiMessageRepository.class);
        AiToolInvocationRepository invocations = mock(AiToolInvocationRepository.class);
        AiToolInvocation running = mock(AiToolInvocation.class);
        AiTurn turn = mock(AiTurn.class);
        when(invocations.findAllByTurn_IdAndStatusIn(10L, List.of("PENDING_APPROVAL", "RUNNING")))
                .thenReturn(List.of(running));
        when(turns.findById(10L)).thenReturn(Optional.of(turn));
        AiChatPersistence persistence = new AiChatPersistence(conversations, turns, messages, invocations);

        persistence.failed(10L, "AI_SESSION_CHANGED", "The Codex session changed before the request completed");

        verify(running).interrupt("AI_SESSION_CHANGED");
        verify(turn).fail("AI_SESSION_CHANGED", "The Codex session changed before the request completed");
    }

    @Test
    void restorationHistoryIsScopedToTheTrustedConversationAndCurrentTurn() {
        AiConversationRepository conversations = mock(AiConversationRepository.class);
        AiTurnRepository turns = mock(AiTurnRepository.class);
        AiMessageRepository messages = mock(AiMessageRepository.class);
        AiToolInvocationRepository invocations = mock(AiToolInvocationRepository.class);
        AiChatPersistence persistence = new AiChatPersistence(conversations, turns, messages, invocations);
        when(invocations.findRestorationHistory(7L, 12L, "configuración de prueba", PageRequest.of(0, 6)))
                .thenReturn(java.util.List.of());

        assertThat(persistence.restorationHistory(7L, 12L, "Configuración de Prueba", 6)).isEmpty();

        verify(invocations).findRestorationHistory(7L, 12L, "configuración de prueba", PageRequest.of(0, 6));
    }

    @Test
    void operationHistoryUsesTheTrustedConversationTurnAndOneExtraRowForPagination() {
        AiToolInvocationRepository invocations = mock(AiToolInvocationRepository.class);
        AiChatPersistence persistence = new AiChatPersistence(mock(AiConversationRepository.class), mock(AiTurnRepository.class),
                mock(AiMessageRepository.class), invocations);
        when(invocations.findOperationHistory(7L, 12L, 34L, PageRequest.of(0, 41))).thenReturn(List.of());

        assertThat(persistence.operationHistory(7L, 12L, 34L, 40)).isEmpty();

        verify(invocations).findOperationHistory(7L, 12L, 34L, PageRequest.of(0, 41));
    }
}
