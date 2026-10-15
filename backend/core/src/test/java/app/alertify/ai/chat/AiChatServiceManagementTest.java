package app.alertify.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import app.alertify.ai.AiToolCatalog;
import app.alertify.ai.localization.AiLocalizationSourceService;
import app.alertify.codex.AiWorkerClient;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.realtime.AdminEventPublisher;
import app.alertify.worker.ai.grpc.ChatWorkerMessage;
import app.alertify.worker.ai.grpc.ChatToolCall;
import tools.jackson.databind.json.JsonMapper;

class AiChatServiceManagementTest {

    @ParameterizedTest
    @ValueSource(strings = { "DIRECT", "READ_ONLY", "PLAN" })
    void sendsWriteVerificationRulesToTheWorkerWithoutChangingTheMode(String mode) {
        AiWorkerClient worker = (AiWorkerClient) ReflectionTestUtils.getField(service, "worker");
        AiChatPersistence persistence = (AiChatPersistence) ReflectionTestUtils.getField(service, "persistence");
        AiToolCatalog catalog = (AiToolCatalog) ReflectionTestUtils.getField(service, "catalog");
        AiLocalizationSourceService localization = (AiLocalizationSourceService) ReflectionTestUtils.getField(service, "localization");
        Authentication authentication = mock(Authentication.class);
        when(authentication.getName()).thenReturn("subject-1");
        when(localization.resolveLocale(any())).thenReturn("en");
        when(worker.state()).thenReturn(app.alertify.worker.ai.grpc.ModuleStateResponse.newBuilder().setEnabled(true).setSessionStatus("ACTIVE").build());
        when(worker.settings()).thenReturn(app.alertify.worker.ai.grpc.AiSettings.newBuilder().setModel("model").setReasoningEffort("low").build());
        when(persistence.start(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(new AiChatPersistence.StartedTurn(7L, 8L));
        when(persistence.history(7L)).thenReturn(List.of());
        when(catalog.descriptorsFor(any(), any())).thenReturn(List.of());
        AiWorkerClient.ChatHandle handle = mock(AiWorkerClient.ChatHandle.class);
        when(worker.chat(any(), any(), any())).thenReturn(handle);

        service.submit(authentication, new AiChatService.SubmitRequest(null, "message", mode, "ASK", "en"));

        var message = org.mockito.ArgumentCaptor.forClass(app.alertify.worker.ai.grpc.ChatClientMessage.class);
        verify(handle).send(message.capture());
        assertThat(message.getValue().getStart().getMode()).isEqualTo(mode);
        assertThat(message.getValue().getStart().getInstructions()).contains(
                "write tool returned success in this turn", "After each successful create or update",
                "After a successful delete", "resource-not-found result is expected",
                "Permission errors, connection errors", "distinguish execution from verification",
                "inspect all result pages", "approval card is the user's confirmation",
                "Each parameterKey must appear only once", "complete replacement list, not a patch",
                "no additional declared value-type restriction", "does not prohibit the corresponding source",
                "A non-empty list restricts the compatible types further", "Still check bindingAllowed, allowedSources",
                "Binary and structured value types remain restricted", "Binding options are global resource metadata",
                "allowedSecretValueTypes=[] can reference an existing STRING secret",
                "Do not read protected values or execute a resource to decide metadata compatibility",
                "never use 0 for the selected reference", "track the next pending step",
                "Do not skip a step or bring a later restoration forward", "changing fileName from TEXT to CONFIGURATION",
                "historical claim such as 'verified' is narrative", "pre-write read and the object returned by create/update",
                "fieldComparison=NOT_RECORDED never certifies verified completion", "Alertify operation audit (metadata only)",
                "Tag IDs and resource IDs are independent", "filter.field=tagId, never tagIds",
                "backend enforces the in-use guard", "not a reason to withhold this guarded ASK proposal",
                "A failed search is unknown, not an empty result", "null with its failed/unverified status",
                "turn failure does not undo successful writes", "only the remaining authorized steps",
                "writable=false still allows authorized administrative changes", "Preserve writable unless",
                "keep enforcing the current mode, approval policy and version checks",
                "configuration_update with the requested stringValue and writable=false",
                "Do not refuse the administrative edit or enable writable just to perform it",
                "USER_APPROVED plus phase=EXECUTION", "TOOL_VERSION_CONFLICT",
                "Outcome metadata refreshes a bounded audit", "prefix uses ~QA-PREFIX*",
                "status NOT_QUERIED", "the associations were not edited",
                "prefer alertify_alert_template_get", "Catalog parameters use key",
                "bindingAllowed=false prohibits resource-reference bindings, not literal TEXT values",
                "select one of the declared options", "SqlWatch.showDetailsInJson",
                "being a separate tag entity does not make that ID invalid",
                "Do not invent a second association ID");
        assertThat(message.getValue().getStart().getToolsList()).filteredOn(tool -> "alertify_operation_history".equals(tool.getName()))
                .singleElement().satisfies(tool -> assertThat(tool.getReadOnly()).isTrue());
        if ("DIRECT".equals(mode)) {
            assertThat(message.getValue().getStart().getInstructions()).contains(
                    "calling a write tool submits a proposal for approval; it does not execute the change yet",
                    "call the matching write tool in this same turn", "the snapshot is preparation",
                    "Do not end the turn merely saying", "ASK alone is not a reason to ask another question",
                    "Expected flow: clear user request", "If approval is rejected, do not execute the change",
                    "Example: the user asks to create a DATE configuration", "fetch the returned ID");
        } else {
            assertThat(message.getValue().getStart().getInstructions()).doesNotContain(
                    "In DIRECT mode with ASK", "Expected flow: clear user request",
                    "Example: the user asks to create a DATE configuration");
        }
        if ("READ_ONLY".equals(mode))
            assertThat(message.getValue().getStart().getInstructions()).contains("cannot mutate it");

        service.cancel(authentication, 8L);
    }

    @Test
    void sendsHistoricalTurnDatesAndAuditStatesWithoutCopyingToolValues() {
        AiWorkerClient worker = (AiWorkerClient) ReflectionTestUtils.getField(service, "worker");
        AiChatPersistence persistence = (AiChatPersistence) ReflectionTestUtils.getField(service, "persistence");
        AiLocalizationSourceService localization = (AiLocalizationSourceService) ReflectionTestUtils.getField(service, "localization");
        Authentication authentication = mock(Authentication.class);
        when(authentication.getName()).thenReturn("subject-1");
        when(localization.resolveLocale(any())).thenReturn("en");
        when(worker.state()).thenReturn(app.alertify.worker.ai.grpc.ModuleStateResponse.newBuilder().setEnabled(true).setSessionStatus("ACTIVE").build());
        when(worker.settings()).thenReturn(app.alertify.worker.ai.grpc.AiSettings.newBuilder().setModel("model").setReasoningEffort("low").build());
        when(persistence.start(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(new AiChatPersistence.StartedTurn(7L, 8L));
        AiTurn previous = mock(AiTurn.class);
        when(previous.getId()).thenReturn(6L);
        when(previous.getCreatedAt()).thenReturn(Instant.parse("2026-10-11T12:00:00Z"));
        when(previous.getStatus()).thenReturn("COMPLETED");
        AiMessage historical = new AiMessage(mock(AiConversation.class), previous, "ASSISTANT", "Previously verified");
        when(persistence.history(7L)).thenReturn(List.of(historical));
        AiToolInvocation operation = new AiToolInvocation(previous, 1, "call", "alertify_alert_update", "{\"id\":25,\"value\":\"PRIVATE-ARG\"}",
                "DOMAIN", true, true, false, false, true, false, "ALERTS", false);
        ReflectionTestUtils.setField(operation, "id", 3L);
        operation.succeed("{\"id\":25,\"value\":\"PRIVATE-RESULT\"}");
        when(persistence.operationHistory(7L, 8L, 0, 40)).thenReturn(List.of(operation));
        AiWorkerClient.ChatHandle handle = mock(AiWorkerClient.ChatHandle.class);
        when(worker.chat(any(), any(), any())).thenReturn(handle);

        service.submit(authentication, new AiChatService.SubmitRequest(7L, "Next change", "DIRECT", "ASK", "en"));

        var message = org.mockito.ArgumentCaptor.forClass(app.alertify.worker.ai.grpc.ChatClientMessage.class);
        verify(handle).send(message.capture());
        var start = message.getValue().getStart();
        assertThat(start.getHistory(0).getRole()).isEqualTo("ASSISTANT");
        assertThat(start.getHistory(0).getContent()).contains("turnId=6", "turnStartedAt=2026-10-11T12:00:00Z", "Previously verified");
        assertThat(start.getInstructions()).contains("\"currentTurnId\":8", "\"turnId\":6", "\"resourceId\":25", "NOT_OBSERVED_IN_WINDOW")
                .doesNotContain("PRIVATE-ARG", "PRIVATE-RESULT");
        service.cancel(authentication, 8L);
    }

    @ParameterizedTest
    @ValueSource(strings = { "{\"beforeInvocationId\":3,\"limit\":2}", "{\"beforeInvocationId\":-1,\"limit\":2}",
            "{\"beforeInvocationId\":0,\"limit\":2147483649}" })
    void operationHistoryIsReadOnlyScopedAndRejectsInvalidPagination(String arguments) {
        AiWorkerClient worker = (AiWorkerClient) ReflectionTestUtils.getField(service, "worker");
        AiChatPersistence persistence = (AiChatPersistence) ReflectionTestUtils.getField(service, "persistence");
        AiLocalizationSourceService localization = (AiLocalizationSourceService) ReflectionTestUtils.getField(service, "localization");
        Authentication authentication = mock(Authentication.class);
        when(authentication.getName()).thenReturn("subject-1");
        when(localization.resolveLocale(any())).thenReturn("en");
        when(worker.state()).thenReturn(app.alertify.worker.ai.grpc.ModuleStateResponse.newBuilder().setEnabled(true).setSessionStatus("ACTIVE").build());
        when(worker.settings()).thenReturn(app.alertify.worker.ai.grpc.AiSettings.newBuilder().setModel("model").setReasoningEffort("low").build());
        when(persistence.start(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(new AiChatPersistence.StartedTurn(7L, 8L));
        AiWorkerClient.ChatHandle handle = mock(AiWorkerClient.ChatHandle.class);
        org.mockito.ArgumentCaptor<Consumer<ChatWorkerMessage>> callback = org.mockito.ArgumentCaptor.forClass(Consumer.class);
        when(worker.chat(callback.capture(), any(), any())).thenReturn(handle);
        AiToolInvocation invocation = mock(AiToolInvocation.class);
        when(invocation.getId()).thenReturn(99L);
        when(invocation.getToolName()).thenReturn("alertify_operation_history");
        when(invocation.isReadOnly()).thenReturn(true);
        when(persistence.tool(any(), anyLong(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), anyBoolean(),
                anyBoolean(), anyBoolean(), anyBoolean(), any(), anyBoolean())).thenReturn(invocation);
        service.submit(authentication, new AiChatService.SubmitRequest(7L, "Check history", "READ_ONLY", "ASK", "en"));

        callback.getValue().accept(ChatWorkerMessage.newBuilder().setToolCall(ChatToolCall.newBuilder()
                .setCallId("history-call").setName("alertify_operation_history").setArguments(arguments)).build());

        var sent = org.mockito.ArgumentCaptor.forClass(app.alertify.worker.ai.grpc.ChatClientMessage.class);
        verify(handle, times(2)).send(sent.capture());
        var result = sent.getAllValues().getLast().getToolResult();
        if (arguments.equals("{\"beforeInvocationId\":3,\"limit\":2}")) {
            verify(persistence).operationHistory(7L, 8L, 3, 2);
            assertThat(result.getFailed()).isFalse();
            assertThat(result.getOutput()).contains("\"currentTurnId\":8");
        } else {
            verify(persistence, times(2)).operationHistory(any(), any(), anyLong(), org.mockito.ArgumentMatchers.anyInt());
            assertThat(result.getFailed()).isTrue();
            assertThat(result.getDiagnosticCode()).isEqualTo("INVALID_OPERATION_HISTORY_QUERY");
        }
        verify(persistence).tool(8L, 1, "history-call", "alertify_operation_history", arguments, "INTERNAL",
                true, true, true, false, true, false, "", false);
        service.cancel(authentication, 8L);
    }

    @Test
    void closesAHandleReturnedAfterTheTurnWasCancelled() {
        AiWorkerClient worker = (AiWorkerClient) ReflectionTestUtils.getField(service, "worker");
        AiChatPersistence persistence = (AiChatPersistence) ReflectionTestUtils.getField(service, "persistence");
        AiToolCatalog catalog = (AiToolCatalog) ReflectionTestUtils.getField(service, "catalog");
        AiLocalizationSourceService localization = (AiLocalizationSourceService) ReflectionTestUtils.getField(service, "localization");
        Authentication authentication = mock(Authentication.class);
        when(authentication.getName()).thenReturn("subject-1");
        when(localization.resolveLocale(any())).thenReturn("en");
        when(worker.state()).thenReturn(app.alertify.worker.ai.grpc.ModuleStateResponse.newBuilder().setEnabled(true).setSessionStatus("ACTIVE").build());
        when(worker.settings()).thenReturn(app.alertify.worker.ai.grpc.AiSettings.newBuilder().setModel("model").setReasoningEffort("low").build());
        when(persistence.start(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(new AiChatPersistence.StartedTurn(7L, 8L));
        when(catalog.descriptorsFor(any(), any())).thenReturn(List.of());
        AiWorkerClient.ChatHandle handle = mock(AiWorkerClient.ChatHandle.class);
        when(worker.chat(any(), any(), any())).thenAnswer(invocation -> {
            service.cancel(authentication, 8L);
            return handle;
        });

        service.submit(authentication, new AiChatService.SubmitRequest(null, "message", "READ_ONLY", "ASK", "en"));

        verify(handle).close();
        verify(handle, never()).send(any());
        verify(persistence).cancelled(8L);
        verify(events, times(1)).publishToSubject(eq("subject-1"), eq("AI_CHAT_UPDATED"), any());
    }

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
                JsonMapper.builder().findAndAddModules().build());
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
