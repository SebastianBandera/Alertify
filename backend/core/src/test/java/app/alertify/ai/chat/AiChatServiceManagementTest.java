package app.alertify.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import app.alertify.ai.AiToolCatalog;
import app.alertify.ai.localization.AiLocalizationSourceService;
import app.alertify.codex.AiWorkerClient;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.realtime.AdminEventPublisher;
import tools.jackson.databind.json.JsonMapper;

class AiChatServiceManagementTest {

    private AiConversationRepository conversations;
    private AiTurnRepository turns;
    private AiMessageRepository messages;
    private AiToolInvocationRepository invocations;
    private AdminEventPublisher events;
    private ApplicationEventLogger eventLogger;
    private AiChatService service;

    @BeforeEach
    void setUp() {
        conversations = mock(AiConversationRepository.class);
        turns = mock(AiTurnRepository.class);
        messages = mock(AiMessageRepository.class);
        invocations = mock(AiToolInvocationRepository.class);
        events = mock(AdminEventPublisher.class);
        eventLogger = mock(ApplicationEventLogger.class);
        service = new AiChatService(mock(AiWorkerClient.class), mock(AiToolCatalog.class), mock(AiChatPersistence.class),
                conversations, turns, messages, invocations, mock(AiLocalizationSourceService.class), events, eventLogger,
                mock(JsonMapper.class));
    }

    @Test
    void returnsAdministrativeConversationDetailsWithPersistedOwnerAndDates() {
        Instant createdAt = Instant.parse("2026-10-11T10:00:00Z");
        Instant updatedAt = Instant.parse("2026-10-11T10:05:00Z");
        AiConversation conversation = conversation(7L, createdAt, updatedAt);
        when(conversations.findById(7L)).thenReturn(Optional.of(conversation));
        when(messages.findAllByConversation_IdOrderById(7L)).thenReturn(List.of());
        when(turns.findAllByConversation_IdOrderById(7L)).thenReturn(List.of());

        AiChatService.ManagedConversationDetail detail = service.managedConversation(7L);

        assertThat(detail.id()).isEqualTo(7L);
        assertThat(detail.userSubject()).isEqualTo("subject-1");
        assertThat(detail.username()).isEqualTo("administrator");
        assertThat(detail.createdAt()).isEqualTo(createdAt);
        assertThat(detail.updatedAt()).isEqualTo(updatedAt);
        assertThat(detail.messages()).isEmpty();
        assertThat(detail.turns()).isEmpty();
    }

    @Test
    void deletesTheConversationAndNotifiesItsOwner() {
        AiConversation conversation = conversation(7L, Instant.now(), Instant.now());
        when(conversations.findById(7L)).thenReturn(Optional.of(conversation));

        service.deleteManagedConversation(7L);

        verify(conversations).delete(conversation);
        verify(eventLogger).successAfterCommit(eq("AI_CONVERSATION_DELETED"), any());
        verify(events).publishToSubject(eq("subject-1"), eq("AI_CHAT_UPDATED"), any());
    }

    @Test
    void deletesEveryConversationAndNotifiesEveryOwner() {
        AiConversation first = conversation(7L, Instant.now(), Instant.now());
        AiConversation second = conversation(8L, Instant.now(), Instant.now());
        when(second.getUserSubject()).thenReturn("subject-2");
        when(conversations.findAllByOrderByUpdatedAtDescIdDesc()).thenReturn(List.of(first, second));

        int deleted = service.deleteAllManagedConversations();

        assertThat(deleted).isEqualTo(2);
        verify(conversations).deleteAllInBatch();
        verify(eventLogger).successAfterCommit(eq("AI_CONVERSATION_DELETED"), any());
        verify(events).publishToSubject(eq("subject-1"), eq("AI_CHAT_UPDATED"), any());
        verify(events).publishToSubject(eq("subject-2"), eq("AI_CHAT_UPDATED"), any());
    }

    @Test
    void localizesThePersistedUserRejectionMessage() {
        assertThat(AiChatService.userRejectionMessage("es-UY", "alertify_configuration_create"))
                .isEqualTo("El usuario rechazó la acción solicitada **alertify_configuration_create**.");
        assertThat(AiChatService.userRejectionMessage("en", "alertify_configuration_create"))
                .isEqualTo("The user rejected the requested action **alertify_configuration_create**.");
    }

    @Test
    void describesTheEffectiveCronTimeZoneAndOffsetForTheModel() {
        String instruction = AiChatService.schedulingTimeZoneInstruction(ZoneId.of("Etc/GMT+3"),
                Instant.parse("2026-10-11T12:00:00Z"));

        assertThat(instruction).contains("Etc/GMT+3").contains("UTC offset -03:00");
    }

    private static AiConversation conversation(Long id, Instant createdAt, Instant updatedAt) {
        AiConversation conversation = mock(AiConversation.class);
        when(conversation.getId()).thenReturn(id);
        when(conversation.getUserSubject()).thenReturn("subject-1");
        when(conversation.getUsername()).thenReturn("administrator");
        when(conversation.getTitle()).thenReturn("Test conversation");
        when(conversation.getMode()).thenReturn("DIRECT");
        when(conversation.getApprovalPolicy()).thenReturn("ASK");
        when(conversation.getStatus()).thenReturn("ACTIVE");
        when(conversation.getCreatedAt()).thenReturn(createdAt);
        when(conversation.getUpdatedAt()).thenReturn(updatedAt);
        return conversation;
    }
}
