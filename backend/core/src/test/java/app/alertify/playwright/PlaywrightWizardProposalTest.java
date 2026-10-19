package app.alertify.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

import app.alertify.codex.AiWorkerClient;
import app.alertify.grpc.WorkerGrpcChannelFactory;
import app.alertify.grpc.discovery.WorkerStatusService;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;
import app.alertify.jpa.repository.ProcedureRepository;
import app.alertify.procedures.execution.ProcedureExecutionOrchestrator;
import app.alertify.procedures.execution.ProcedureInvocationRegistry;
import app.alertify.realtime.AdminEventPublisher;
import app.alertify.worker.ai.grpc.ChatClientMessage;
import app.alertify.worker.ai.grpc.ChatToolCall;
import app.alertify.worker.ai.grpc.ChatToolResult;
import app.alertify.worker.wizard.grpc.WizardEvent;
import app.alertify.worker.wizard.grpc.WizardRequest;
import io.grpc.stub.StreamObserver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Exercises proposal handling without opening a browser or resolving any credential. */
class PlaywrightWizardProposalTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final WizardDraftStore store = mock(WizardDraftStore.class);
    private final PlaywrightValuePreparationService values = mock(PlaywrightValuePreparationService.class);
    private final AiWorkerClient.ChatHandle chat = mock(AiWorkerClient.ChatHandle.class);
    private final List<ChatToolResult> replies = new ArrayList<>();
    private final List<WizardRequest> browserRequests = new ArrayList<>();
    private final PlaywrightWizardService service = new PlaywrightWizardService(store, mock(WorkerStatusService.class), mock(WorkerGrpcChannelFactory.class), mock(AiWorkerClient.class),
            mock(AdminEventPublisher.class), mock(ApplicationConfigurationRepository.class), values, mock(ProcedureExecutionOrchestrator.class), mock(ProcedureInvocationRegistry.class), mock(ProcedureRepository.class), json);
    private WizardDraft draft;
    private Object session;

    @Test
    void rejectsAnIncompleteCheckWithoutExecutionAndAcceptsItsCorrectionWithoutRestarting() throws Exception {
        prepare("ASK");
        var original = document();
        var rejected = steps("QUERY #login\nUSE\nWAIT_VISIBLE main 10s\nQUERY main\nCHECK TEXT");
        assertThat(rejected.getFailed()).isTrue();
        assertThat(rejected.getDiagnosticCode()).isEqualTo("PLAYWRIGHT_STEPS_INVALID");
        var failure = document().path("proposalError");
        assertThat(failure.path("error").asString()).contains("Line 6", "CHECK TEXT requires an argument");
        assertThat(failure.path("executed").asBoolean()).isFalse();
        assertThat(failure.path("retryable").asBoolean()).isTrue();
        assertThat(failure.path("remainingCorrections").asInt()).isEqualTo(2);
        assertThat(document().path("settings")).isEqualTo(original.path("settings"));
        assertThat(document().path("lastAction")).isEqualTo(original.path("lastAction"));
        assertThat(document().path("testedRevision")).isEqualTo(original.path("testedRevision"));
        assertThat(ReflectionTestUtils.getField(session, "restartRequired")).isEqualTo(false);
        assertThat(browserRequests).isEmpty();
        verifyNoInteractions(values);
        String corrected = "QUERY #login\nUSE\nWAIT_VISIBLE main 10s\nQUERY main\nCHECK TEXT Área autenticada";
        var accepted = steps(corrected);
        assertThat(accepted.getFailed()).isFalse();
        assertThat(accepted.getOutput()).contains("PENDING_APPROVAL");
        assertThat(document().path("proposal").asString()).isEqualTo(corrected);
        assertThat(document().has("proposalError")).isFalse();
        assertThat(browserRequests).isEmpty();
    }

    @Test
    void stopsAutomaticCorrectionsAfterThreeRejectedBlocksAndPreservesTheExactDiagnostic() throws Exception {
        prepare("ASK");
        for (int attempt = 0; attempt < 3; attempt++) steps("CHECK TEXT");

        var failure = document().path("proposalError");
        assertThat(failure.path("retryable").asBoolean()).isFalse();
        assertThat(failure.path("remainingCorrections").asInt()).isZero();
        var extra = steps("QUERY #login\nUSE");
        assertThat(extra.getFailed()).isTrue();
        assertThat(document().path("proposalError").path("error")).isEqualTo(failure.path("error"));
        assertThat(document().has("proposal")).isFalse();
        assertThat(browserRequests).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void redactsKnownValuesFromPersistedAndReturnedValidationErrors() throws Exception {
        prepare("ASK");
        String canary = "synthetic-private-" + UUID.randomUUID();
        ((List<String>) ReflectionTestUtils.getField(session, "sensitive")).add(canary);
        var result = steps(canary);
        assertThat(result.getOutput()).doesNotContain(canary).contains("[REDACTED]");
        assertThat(result.getOperationMetadataJson()).doesNotContain(canary);
        assertThat(draft.getDocument()).doesNotContain(canary);
        assertThat(browserRequests).isEmpty();
    }

    @Test
    void rejectsMalformedToolArgumentsBeforeAnyBrowserRequest() throws Exception {
        prepare("ASK");
        var result = arguments("{");
        assertThat(result.getDiagnosticCode()).isEqualTo("PLAYWRIGHT_STEPS_INVALID");
        assertThat(document().path("proposalError").path("error").asString()).contains("valid browser_steps JSON");
        assertThat(document().path("proposalError").path("retryable").asBoolean()).isTrue();
        assertThat(arguments("{\"steps\":12}").getFailed()).isTrue();
        assertThat(browserRequests).isEmpty();
    }

    @Test
    void aProcedurePermissionFailureDoesNotAuthorizeAutomaticCorrectionOrExecution() throws Exception {
        prepare("ASK");
        var procedure = mock(app.alertify.procedures.model.Procedure.class);
        when(procedure.getId()).thenReturn(7L);
        when(values.procedure("Fixture")).thenReturn(procedure);
        steps("CALL PROCEDURE \"Fixture\" POINTER \"/code\" AS code");
        assertThat(document().path("proposalError").path("retryable").asBoolean()).isFalse();
        assertThat(document().path("proposalError").path("error").asString()).contains("not enabled");
        assertThat(browserRequests).isEmpty();
    }

    @Test
    void anAutoExecutionFailureRequiresRestartAndNeverBecomesARetryableProposalError() throws Exception {
        prepare("AUTO");
        var failed = steps("QUERY #login\nUSE");
        assertThat(failed.getFailed()).isTrue();
        assertThat(failed.getDiagnosticCode()).isEmpty();
        assertThat(document().path("lastAction").path("status").asString()).isEqualTo("FAILED");
        assertThat(document().has("proposalError")).isFalse();
        assertThat(ReflectionTestUtils.getField(session, "restartRequired")).isEqualTo(true);
        assertThat(browserRequests).hasSize(1);
        steps("QUERY #login\nUSE");
        assertThat(browserRequests).hasSize(1);
        assertThat(document().has("proposalError")).isFalse();
        assertThat(arguments("{").getDiagnosticCode()).isEmpty();
        assertThat(document().has("proposalError")).isFalse();
    }

    @SuppressWarnings("unchecked")
    private void prepare(String mode) throws Exception {
        var settings = new PlaywrightWizardService.Settings("https://fixture.invalid/", "QUERY #username", "chromium", List.of("chromium"), 10, 5, mode);
        draft = new WizardDraft("admin-subject", json.writeValueAsString(Map.of("settings", settings, "messages", List.of(), "testedRevision", "passing-revision", "lastAction", Map.of("operation", "EXECUTE", "status", "SUCCESS", "result", Map.of("completed", 4)))));
        when(store.load("admin-subject", draft.getId())).thenReturn(draft);
        doAnswer(invocation -> { draft.update(invocation.getArgument(2)); return null; }).when(store).save(any(), any(), any());
        var authentication = new UsernamePasswordAuthenticationToken("admin-subject", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        Class<?> sessionType = Class.forName(PlaywrightWizardService.class.getName() + "$Session");
        var constructor = sessionType.getDeclaredConstructor(PlaywrightWizardService.class, UUID.class, String.class, String.class, org.springframework.security.core.Authentication.class);
        constructor.setAccessible(true);
        session = constructor.newInstance(service, draft.getId(), "admin-subject", "browser-a", authentication);
        ReflectionTestUtils.setField(session, "chat", chat);
        ReflectionTestUtils.setField(session, "browserSettings", settings);
        doAnswer(invocation -> { ChatClientMessage frame = invocation.getArgument(0); if (frame.hasToolResult()) replies.add(frame.getToolResult()); return null; }).when(chat).send(any());
        StreamObserver<WizardRequest> browser = mock(StreamObserver.class);
        doAnswer(invocation -> {
            WizardRequest request = invocation.getArgument(0);
            browserRequests.add(request);
            var pending = (ConcurrentMap<String, CompletableFuture<WizardEvent>>) ReflectionTestUtils.getField(session, "pending");
            pending.get(request.getRequestId()).completeExceptionally(new IllegalArgumentException("Synthetic browser action failure"));
            return null;
        }).when(browser).onNext(any());
        ReflectionTestUtils.setField(session, "requests", browser);
    }

    private ChatToolResult steps(String block) {
        return arguments(json.writeValueAsString(Map.of("steps", block)));
    }

    private ChatToolResult arguments(String arguments) {
        var call = ChatToolCall.newBuilder().setCallId(UUID.randomUUID().toString()).setName("browser_steps").setArguments(arguments).build();
        ReflectionTestUtils.invokeMethod(service, "handleTool", session, call, new CompletableFuture<Void>());
        return replies.getLast();
    }

    private JsonNode document() { return json.readTree(draft.getDocument()); }
}
