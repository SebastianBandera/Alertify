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

        persistence.rejectedByUser(12L, "El usuario rechazó la acción solicitada **alertify_configuration_create**.");

        verify(invocation).reject("Rejected by the user", "USER_REJECTED");
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
