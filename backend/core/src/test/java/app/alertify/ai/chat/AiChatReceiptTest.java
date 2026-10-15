package app.alertify.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import app.alertify.api.error.ConflictException;
import app.alertify.codex.AiWorkerClient;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.realtime.AdminEventPublisher;
import app.alertify.worker.ai.grpc.ChatClientMessage;
import app.alertify.worker.ai.grpc.ChatToolCall;
import app.alertify.worker.ai.grpc.ChatWorkerMessage;
import tools.jackson.databind.json.JsonMapper;

class AiChatReceiptTest {
    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void approvedExecutionReportsItsActualPhaseWithoutCopyingProtectedValuesIntoMetadata(boolean failed) {
        var fixture = fixture("DIRECT", "ASK");
        if (failed)
            when(fixture.callback.call(any())).thenThrow(new IllegalStateException(ConflictException.versionConflict("PRIVATE-ERROR")));
        else
            when(fixture.callback.call(any())).thenReturn("{\"id\":10,\"value\":\"PRIVATE-RESULT\"}");

        fixture.call();
        verify(fixture.callback, never()).call(any());
        fixture.service.approve(fixture.authentication, 99L);
        var result = fixture.result();
        var receipt = JSON.readTree(result.getOperationMetadataJson());
        assertThat(receipt.path("phase").stringValue()).isEqualTo("EXECUTION");
        assertThat(receipt.path("approvalDecision").stringValue()).isEqualTo("USER_APPROVED");
        assertThat(receipt.path("status").stringValue()).isEqualTo(failed ? "FAILED" : "SUCCEEDED");
        assertThat(receipt.path("resourceId").longValue()).isEqualTo(10L);
        assertThat(receipt.path("timeZone").stringValue()).isEqualTo("UTC");
        assertThat(receipt.path("reportedAt").stringValue()).isNotBlank();
        assertThat(receipt.path("fieldComparison").stringValue()).isEqualTo("NOT_RECORDED");
        assertThat(result.getOperationMetadataJson()).doesNotContain("PRIVATE", "reviewReason", "arguments", "value");
        assertThat(result.getFailed()).isEqualTo(failed);
        if (failed)
            assertThat(result.getDiagnosticCode()).isEqualTo("TOOL_VERSION_CONFLICT");
        else
            assertThat(result.getOutput()).isEqualTo("{\"id\":10,\"value\":\"PRIVATE-RESULT\"}");

        fixture.service.cancel(fixture.authentication, 8L);
    }

    @Test
    void modeDenialIsPreExecutionAndNeverClaimsAnAskApproval() {
        var fixture = fixture("READ_ONLY", "ASK");
        fixture.call();
        var result = fixture.result();
        var receipt = JSON.readTree(result.getOperationMetadataJson());
        assertThat(receipt.path("phase").stringValue()).isEqualTo("PRE_EXECUTION");
        assertThat(receipt.path("approvalDecision").stringValue()).isEqualTo("NOT_REQUESTED");
        assertThat(result.getDiagnosticCode()).isEqualTo("TOOL_NOT_ALLOWED");
        verify(fixture.callback, never()).call(any());
        fixture.service.cancel(fixture.authentication, 8L);
    }

    @Test
    void unavailableAuditDoesNotChangeASuccessfulWriteOutcome() {
        var fixture = fixture("DIRECT", "ASK");
        when(fixture.callback.call(any())).thenReturn("{\"id\":10}");
        fixture.call();
        when(fixture.persistence.operationHistory(any(), any(), anyLong(), anyInt())).thenThrow(new IllegalStateException("PRIVATE-DB"));
        fixture.service.approve(fixture.authentication, 99L);
        var result = fixture.result();
        assertThat(result.getFailed()).isFalse();
        assertThat(result.getOutput()).isEqualTo("{\"id\":10}");
        assertThat(JSON.readTree(result.getOperationMetadataJson()).path("auditAvailable").booleanValue()).isFalse();
        assertThat(result.getOperationMetadataJson()).doesNotContain("PRIVATE-DB");
        fixture.service.cancel(fixture.authentication, 8L);
    }

    @Test
    void userRejectionCancelsWithoutExecutionOrAFabricatedToolResult() {
        var fixture = fixture("DIRECT", "ASK");
        fixture.call();
        fixture.service.reject(fixture.authentication, 99L);
        verify(fixture.callback, never()).call(any());
        verify(fixture.persistence).rejectedByUser(org.mockito.ArgumentMatchers.eq(99L), any());
        verify(fixture.handle).close();
        verify(fixture.handle, org.mockito.Mockito.times(1)).send(any());
    }

    @Test
    void automaticReviewRejectionIsNotAnExecutionFailureOrAHumanApproval() {
        var fixture = fixture("DIRECT", "AUTO");
        when(fixture.worker.review(any())).thenReturn(app.alertify.worker.ai.grpc.ReviewToolCallResponse.newBuilder()
                .setAllowed(false).setReason("PRIVATE-REVIEW").build());
        fixture.call();
        var result = fixture.result();
        var receipt = JSON.readTree(result.getOperationMetadataJson());
        assertThat(receipt.path("phase").stringValue()).isEqualTo("REVIEW");
        assertThat(receipt.path("approvalDecision").stringValue()).isEqualTo("AUTO_REJECTED");
        assertThat(receipt.path("status").stringValue()).isEqualTo("REJECTED");
        assertThat(receipt.path("diagnosticCode").stringValue()).isEqualTo("REVIEW_REJECTED");
        assertThat(result.getOperationMetadataJson()).doesNotContain("PRIVATE-REVIEW", "USER_APPROVED");
        verify(fixture.callback, never()).call(any());
        fixture.service.cancel(fixture.authentication, 8L);
    }

    private Fixture fixture(String mode, String approval) {
        var worker = mock(AiWorkerClient.class);
        var catalog = mock(AiToolCatalog.class);
        var persistence = mock(AiChatPersistence.class);
        var localization = mock(AiLocalizationSourceService.class);
        var authentication = mock(Authentication.class);
        when(authentication.getName()).thenReturn("subject-1");
        when(localization.resolveLocale(any())).thenReturn("en");
        when(worker.state()).thenReturn(app.alertify.worker.ai.grpc.ModuleStateResponse.newBuilder().setEnabled(true).setSessionStatus("ACTIVE").build());
        when(worker.settings()).thenReturn(app.alertify.worker.ai.grpc.AiSettings.newBuilder().setModel("model").setReasoningEffort("low").build());
        when(persistence.start(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(new AiChatPersistence.StartedTurn(7L,8L));
        var callback = mock(ToolCallback.class);
        var definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("alertify_configuration_update");
        when(definition.description()).thenReturn("synthetic update");
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.getToolMetadata()).thenReturn(ToolMetadata.builder().build());
        var descriptor = new AiToolDescriptor(callback, "alertify_configuration_update", "synthetic update", false,
                DefaultToolCallResultConverter.class, false, false, false, false, Set.of());
        when(catalog.descriptorsFor(any(), any())).thenReturn(List.of(descriptor));
        var turn = mock(AiTurn.class);
        when(turn.getId()).thenReturn(8L);
        var invocation = new AiToolInvocation(turn,1,"synthetic-call","alertify_configuration_update",
                "{\"id\":10,\"value\":\"PRIVATE-ARG\"}","DOMAIN",true,true,false,false,false,false,"",true);
        ReflectionTestUtils.setField(invocation,"id",99L);
        when(persistence.tool(any(),anyLong(),any(),any(),any(),any(),anyBoolean(),anyBoolean(),anyBoolean(),
                anyBoolean(),anyBoolean(),anyBoolean(),any(),anyBoolean())).thenReturn(invocation);
        when(persistence.approvalIsValid(99L,"subject-1")).thenReturn(true);
        var handle = mock(AiWorkerClient.ChatHandle.class);
        ArgumentCaptor<Consumer<ChatWorkerMessage>> consumer = ArgumentCaptor.forClass(Consumer.class);
        when(worker.chat(consumer.capture(),any(),any())).thenReturn(handle);
        var service = new AiChatService(worker,catalog,persistence,mock(AiConversationRepository.class),mock(AiTurnRepository.class),
                mock(AiMessageRepository.class),mock(AiToolInvocationRepository.class),localization,mock(AdminEventPublisher.class),
                mock(ApplicationEventLogger.class),JSON);
        service.submit(authentication,new AiChatService.SubmitRequest(null,"synthetic change",mode,approval,"en"));
        return new Fixture(service,persistence,authentication,callback,consumer.getValue(),handle,worker);
    }

    private record Fixture(AiChatService service, AiChatPersistence persistence, Authentication authentication,
            ToolCallback callback, Consumer<ChatWorkerMessage> consumer, AiWorkerClient.ChatHandle handle, AiWorkerClient worker) {
        void call() {
            consumer.accept(ChatWorkerMessage.newBuilder().setToolCall(ChatToolCall.newBuilder().setCallId("synthetic-call")
                    .setName("alertify_configuration_update").setArguments("{\"id\":10,\"value\":\"PRIVATE-ARG\"}")).build());
        }

        app.alertify.worker.ai.grpc.ChatToolResult result() {
            var messages = ArgumentCaptor.forClass(ChatClientMessage.class);
            verify(handle,org.mockito.Mockito.atLeast(2)).send(messages.capture());
            return messages.getAllValues().getLast().getToolResult();
        }
    }
}
