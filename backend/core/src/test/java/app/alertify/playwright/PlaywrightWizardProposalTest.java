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
    private final List<String> selectorChecks = new ArrayList<>();
    private JsonNode selectorReport;
    private final PlaywrightWizardService service = new PlaywrightWizardService(store, mock(WorkerStatusService.class), mock(WorkerGrpcChannelFactory.class), mock(AiWorkerClient.class),
            mock(AdminEventPublisher.class), mock(ApplicationConfigurationRepository.class), values, mock(ProcedureExecutionOrchestrator.class), mock(ProcedureInvocationRegistry.class), mock(ProcedureRepository.class), mock(WizardAlertPlanService.class), json);
    private WizardDraft draft;
    private Object session;

    @Test
    @SuppressWarnings("unchecked")
    void manualMaskChangesAreSessionOnlyAndUnmaskedFramesCannotBeAttached() throws Exception {
        prepare("ASK");
        ((Map<UUID, Object>) ReflectionTestUtils.getField(service, "sessions")).put(draft.getId(), session);
        var authentication = new UsernamePasswordAuthenticationToken("admin-subject", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        StreamObserver<WizardRequest> browser = mock(StreamObserver.class);
        doAnswer(invocation -> {
            WizardRequest request = invocation.getArgument(0);
            browserRequests.add(request);
            var pending = (ConcurrentMap<String, CompletableFuture<WizardEvent>>) ReflectionTestUtils.getField(session, "pending");
            pending.get(request.getRequestId()).complete(WizardEvent.newBuilder().setType("RESULT").setJson("{}").build());
            return null;
        }).when(browser).onNext(any());
        ReflectionTestUtils.setField(session, "requests", browser);
        ReflectionTestUtils.setField(session, "attachment", WizardEvent.newBuilder().setFrameId(7).build());
        var frames = (Map<Long, WizardEvent>) ReflectionTestUtils.getField(session, "recentFrames");
        frames.put(7L, WizardEvent.newBuilder().setFrameId(7).build());
        String original = draft.getDocument();
        service.request(authentication, json.valueToTree(Map.of("operation", "SET_MASK", "id", draft.getId(), "clientId", "browser-a", "enabled", false)));
        awaitOperation();
        assertThat(ReflectionTestUtils.getField(session, "maskEnabled")).isEqualTo(false);
        assertThat(ReflectionTestUtils.getField(session, "attachment")).isNull();
        assertThat(frames).isEmpty();
        assertThat(draft.getDocument()).isEqualTo(original);
        var state = json.valueToTree(service.request(authentication, json.valueToTree(Map.of("operation", "GET", "id", draft.getId()))));
        assertThat(state.path("maskEnabled").asBoolean()).isFalse();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.request(authentication,
                json.valueToTree(Map.of("operation", "ATTACH", "id", draft.getId(), "clientId", "browser-a", "frameId", 7))))
                .hasMessageContaining("Enable the view mask");
        service.request(authentication, json.valueToTree(Map.of("operation", "SET_MASK", "id", draft.getId(), "clientId", "browser-a", "enabled", true)));
        awaitOperation();
        assertThat(ReflectionTestUtils.getField(session, "maskEnabled")).isEqualTo(true);
        assertThat(browserRequests).extracting(WizardRequest::getOperation).containsExactly("SET_MASK", "SET_MASK");
        assertThat(draft.getDocument()).isEqualTo(original);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.request(authentication,
                json.valueToTree(Map.of("operation", "SET_MASK", "id", draft.getId(), "clientId", "other-window", "enabled", false))))
                .hasMessageContaining("another assistant window");
    }

    @Test
    void aiCannotToggleTheMaskThroughToolsOrScriptCommands() throws Exception {
        prepare("AUTO");
        for (String name : List.of("SET_MASK", "browser_set_mask")) {
            var call = ChatToolCall.newBuilder().setCallId(UUID.randomUUID().toString()).setName(name).setArguments("{\"enabled\":false}").build();
            ReflectionTestUtils.invokeMethod(service, "handleTool", session, call, new CompletableFuture<Void>());
            assertThat(replies.getLast().getFailed()).isTrue();
        }
        assertThat(steps("SET_MASK false").getFailed()).isTrue();
        assertThat(browserRequests).isEmpty();
        assertThat(ReflectionTestUtils.getField(session, "maskEnabled")).isEqualTo(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aiCaptureCannotOverrideProtectionEvenWhenTheUserUnmasksTheView() throws Exception {
        prepare("AUTO");
        ReflectionTestUtils.setField(session, "maskEnabled", false);
        StreamObserver<WizardRequest> browser = mock(StreamObserver.class);
        doAnswer(invocation -> {
            WizardRequest request = invocation.getArgument(0);
            browserRequests.add(request);
            var pending = (ConcurrentMap<String, CompletableFuture<WizardEvent>>) ReflectionTestUtils.getField(session, "pending");
            pending.get(request.getRequestId()).complete(WizardEvent.newBuilder().setType("IMAGE").setImage(com.google.protobuf.ByteString.copyFromUtf8("fixture-image")).build());
            return null;
        }).when(browser).onNext(any());
        ReflectionTestUtils.setField(session, "requests", browser);
        var call = ChatToolCall.newBuilder().setCallId(UUID.randomUUID().toString()).setName("browser_capture").setArguments("{\"masked\":false,\"enabled\":false}").build();
        ReflectionTestUtils.invokeMethod(service, "handleTool", session, call, new CompletableFuture<Void>());
        assertThat(replies.getLast().getFailed()).isFalse();
        assertThat(browserRequests).singleElement().satisfies(request -> {
            assertThat(request.getOperation()).isEqualTo("SCREENSHOT");
            assertThat(json.readTree(request.getJson()).isEmpty()).isTrue();
        });
        assertThat(ReflectionTestUtils.getField(session, "maskEnabled")).isEqualTo(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void sanitizesEveryProviderContextSourceAndKeepsReferencePointersExact() throws Exception {
        prepare("ASK");
        String canary = "fixture-provider-private";
        ((List<String>) ReflectionTestUtils.getField(session, "sensitive")).addAll(List.of(canary, "1"));
        var doc = (tools.jackson.databind.node.ObjectNode) document();
        ((tools.jackson.databind.node.ObjectNode) doc.path("settings")).put("steps", "CALL PROCEDURE \"Fixture\" POINTER \"/items/1\" AS code1");
        doc.set("messages", json.valueToTree(List.of(Map.of("role", "USER", "content", "Historical " + canary))));
        draft.update(json.writeValueAsString(doc));
        ReflectionTestUtils.setField(session, "selection", json.valueToTree(Map.of("selector", "[data-token=\"" + canary + "\"]")));
        var ai = (AiWorkerClient) ReflectionTestUtils.getField(service, "ai");
        when(ai.state()).thenReturn(app.alertify.worker.ai.grpc.ModuleStateResponse.newBuilder().setEnabled(true).setSessionStatus("ACTIVE").build());
        when(ai.settings()).thenReturn(app.alertify.worker.ai.grpc.AiSettings.newBuilder().setModel("fixture-model").setReasoningEffort("low").build());
        when(values.secretReferences()).thenReturn(List.of());
        var completion = new java.util.concurrent.atomic.AtomicReference<Runnable>();
        when(ai.chat(any(), any(), any())).thenAnswer(invocation -> { completion.set(invocation.getArgument(2)); return chat; });
        var starts = new ArrayList<app.alertify.worker.ai.grpc.ChatStart>();
        doAnswer(invocation -> {
            ChatClientMessage message = invocation.getArgument(0);
            if (message.hasStart()) { starts.add(message.getStart()); completion.get().run(); }
            return null;
        }).when(chat).send(any());
        StreamObserver<WizardRequest> browser = mock(StreamObserver.class);
        doAnswer(invocation -> {
            WizardRequest request = invocation.getArgument(0);
            var pending = (ConcurrentMap<String, CompletableFuture<WizardEvent>>) ReflectionTestUtils.getField(session, "pending");
            pending.get(request.getRequestId()).complete(WizardEvent.newBuilder().setType("RESULT").setJson(json.writeValueAsString(Map.of("snapshot", "Remote " + canary))).build());
            return null;
        }).when(browser).onNext(any());
        ReflectionTestUtils.setField(session, "requests", browser);
        ReflectionTestUtils.invokeMethod(service, "chat", session, "Current request " + canary, "es-UY");
        assertThat(starts).hasSize(1);
        var start = starts.getFirst();
        assertThat(start.getInstructions()).doesNotContain(canary).contains("/items/1", "[REDACTED]");
        assertThat(start.getHistoryList()).allSatisfy(message -> assertThat(message.getContent()).doesNotContain(canary));
        assertThat(draft.getDocument()).doesNotContain(canary);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsSensitiveProposalsInAskAndAutoWithoutSavingOrDispatching() throws Exception {
        for (String mode : List.of("ASK", "AUTO")) {
            prepare(mode);
            String canary = "fixture-proposal-private";
            ((List<String>) ReflectionTestUtils.getField(session, "sensitive")).add(canary);
            String original = draft.getDocument();
            var reply = steps("QUERY main\nCHECK TEXT " + canary);
            assertThat(reply.getFailed()).isTrue();
            assertThat(reply.getDiagnosticCode()).isEqualTo(PlaywrightDraftProtection.CODE);
            assertThat(reply.getOutput()).doesNotContain(canary);
            assertThat(draft.getDocument()).isEqualTo(original);
            assertThat(browserRequests).isEmpty();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsLegacySensitiveApprovalAndActiveSaveBeforeAnyMutation() throws Exception {
        prepare("ASK");
        String canary = "fixture-approval-private";
        ((List<String>) ReflectionTestUtils.getField(session, "sensitive")).add(canary);
        var sessions = (Map<UUID, Object>) ReflectionTestUtils.getField(service, "sessions");
        sessions.put(draft.getId(), session);
        var doc = (tools.jackson.databind.node.ObjectNode) document();
        doc.put("proposal", "QUERY main\nCHECK TEXT " + canary);
        draft.update(json.writeValueAsString(doc));
        String original = draft.getDocument();
        var authentication = new UsernamePasswordAuthenticationToken("admin-subject", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        var request = json.valueToTree(Map.of("operation", "APPROVE", "id", draft.getId(), "clientId", "browser-a"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.request(authentication, request)).isInstanceOf(PlaywrightDraftProtection.SensitiveContentFailure.class);
        assertThat(draft.getDocument()).isEqualTo(original);
        var settings = json.treeToValue(doc.path("settings"), PlaywrightWizardService.Settings.class).withSteps("QUERY [data-token=\"" + canary + "\"]");
        var save = json.valueToTree(Map.of("operation", "SAVE", "id", draft.getId(), "clientId", "browser-a", "settings", settings));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.request(authentication, save)).isInstanceOf(PlaywrightDraftProtection.SensitiveContentFailure.class);
        assertThat(draft.getDocument()).isEqualTo(original);
        assertThat(browserRequests).isEmpty();
    }

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

    @Test
    void rejectsAnAmbiguousFirstActionBeforeSavingOrExecutingInAskAndAuto() throws Exception {
        for (String mode : List.of("ASK", "AUTO")) {
            prepare(mode);
            selectorReport = json.valueToTree(Map.of("visibleCount", 3, "totalCount", 5, "ambiguous", true, "matches", List.of(
                    Map.of("label", "Autoridades", "href", "/anep/autoridades"), Map.of("label", "Autoridades", "href", "/codicen/autoridades"))));
            String original = draft.getDocument();
            var reply = steps("WAIT_VISIBLE a:text-is(\"Autoridades\") 10s\nQUERY a:text-is(\"Autoridades\")\nUSE");
            assertThat(reply.getFailed()).isTrue();
            assertThat(reply.getDiagnosticCode()).isEqualTo("PLAYWRIGHT_SELECTOR_AMBIGUOUS");
            var receipt = json.readTree(reply.getOutput());
            assertThat(receipt.path("executed").asBoolean()).isFalse();
            assertThat(receipt.path("matches").path("visibleCount").asInt()).isEqualTo(3);
            assertThat(reply.getOutput()).contains("/anep/autoridades", "/codicen/autoridades");
            assertThat(draft.getDocument()).isEqualTo(original);
            assertThat(browserRequests).isEmpty();
            assertThat(selectorChecks).containsExactly("a:text-is(\"Autoridades\")");
            assertThat(ReflectionTestUtils.getField(session, "restartRequired")).isEqualTo(false);
        }
    }

    @Test
    void accessibilityActivationDefersSelectorChecksUntilSemanticsExist() throws Exception {
        prepare("ASK");
        var reply = steps("ENABLE_ACCESSIBILITY\nWAIT_VISIBLE [role=button] 10s\nQUERY [role=button]\nUSE");
        assertThat(reply.getFailed()).isFalse();
        assertThat(selectorChecks).isEmpty();
        assertThat(browserRequests).isEmpty();
        assertThat(document().path("proposal").asString()).startsWith("ENABLE_ACCESSIBILITY");
    }

    @Test
    void checksOnlyTheFirstActionWithoutPredictingTheStateAfterHover() throws Exception {
        prepare("ASK");
        var reply = steps("WAIT_VISIBLE #menu 10s\nQUERY #menu\nHOVER\nWAIT_VISIBLE a:text-is(\"Autoridades\") 10s\nQUERY a:text-is(\"Autoridades\")\nUSE");
        assertThat(reply.getFailed()).isFalse();
        assertThat(selectorChecks).containsExactly("#menu");
        assertThat(browserRequests).isEmpty();
        assertThat(document().path("proposal").asString()).contains("HOVER");
    }

    @Test
    @SuppressWarnings("unchecked")
    void exposesReadOnlySelectorChecksWithoutLeakingProtectedMatchDetails() throws Exception {
        prepare("ASK");
        String canary = "selector-private-canary";
        ((List<String>) ReflectionTestUtils.getField(session, "sensitive")).add(canary);
        selectorReport = json.valueToTree(Map.of("visibleCount", 2, "matches", List.of(Map.of("label", canary, "href", "/public?token=" + canary))));
        var call = ChatToolCall.newBuilder().setCallId(UUID.randomUUID().toString()).setName("browser_check_selector").setArguments("{\"selector\":\"a\"}").build();
        ReflectionTestUtils.invokeMethod(service, "handleTool", session, call, new CompletableFuture<Void>());
        var reply = replies.getLast();
        assertThat(reply.getFailed()).isFalse();
        assertThat(reply.getOutput()).contains("[REDACTED]").doesNotContain(canary);
        assertThat(selectorChecks).containsExactly("a");
        assertThat(browserRequests).isEmpty();
    }

    @Test
    void replayWaitsForResetThenExecutesTheSavedScriptAndKeepsProposalsPending() throws Exception {
        var reset = new CompletableFuture<WizardRequest>();
        prepareReplay("", reset);
        var doc = (tools.jackson.databind.node.ObjectNode) document();
        doc.put("proposal", "QUERY #not-approved\nUSE");
        draft.update(json.writeValueAsString(doc));
        requestOperation("REPLAY");
        WizardRequest resetRequest = reset.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(browserRequests).extracting(WizardRequest::getOperation).containsExactly("RESET");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> requestOperation("REPLAY")).hasMessageContaining("Another operation");
        completeBrowserRequest(resetRequest, "");
        awaitOperation();
        assertThat(browserRequests).extracting(WizardRequest::getOperation).containsExactly("RESET", "EXECUTE");
        assertThat(json.readTree(browserRequests.getLast().getJson()).path("steps").asString()).isEqualTo("QUERY #username");
        assertThat(json.readTree(resetRequest.getJson()).path("engine").asString()).isEqualTo("chromium");
        assertThat(document().path("proposal").asString()).isEqualTo("QUERY #not-approved\nUSE");
        assertThat(document().path("lastAction").path("status").asString()).isEqualTo("SUCCESS");
        assertThat(document().path("lastAction").path("result").path("completed").asInt()).isEqualTo(1);
        assertThat(document().has("testedRevision")).isFalse();
        assertThat(ReflectionTestUtils.getField(session, "restartRequired")).isEqualTo(false);
        assertThat(ReflectionTestUtils.getField(session, "closed")).hasToString("false");
    }

    @Test
    void replayDoesNotExecuteIfResetFailsAndRetainsTheRestartBarrier() throws Exception {
        prepareReplay("RESET", null);
        requestOperation("REPLAY");
        awaitOperation();
        assertThat(browserRequests).extracting(WizardRequest::getOperation).containsExactly("RESET");
        assertThat(document().path("lastAction").path("operation").asString()).isEqualTo("REPLAY");
        assertThat(document().path("lastAction").path("status").asString()).isEqualTo("FAILED");
        assertThat(document().path("lastAction").path("result").path("completed").asInt()).isZero();
        assertThat(document().has("testedRevision")).isFalse();
        assertThat(ReflectionTestUtils.getField(session, "restartRequired")).isEqualTo(true);
    }

    @Test
    void replayStopsAfterAnExecutionFailureAndRequiresAnotherExplicitRestart() throws Exception {
        prepareReplay("EXECUTE", null);
        requestOperation("REPLAY");
        awaitOperation();
        assertThat(browserRequests).extracting(WizardRequest::getOperation).containsExactly("RESET", "EXECUTE");
        assertThat(document().path("lastAction").path("operation").asString()).isEqualTo("EXECUTE");
        assertThat(document().path("lastAction").path("status").asString()).isEqualTo("FAILED");
        assertThat(ReflectionTestUtils.getField(session, "restartRequired")).isEqualTo(true);
    }

    @Test
    void urlOnlyResetDoesNotReplayAnySteps() throws Exception {
        prepareReplay("", null);
        requestOperation("RESET");
        awaitOperation();
        assertThat(browserRequests).extracting(WizardRequest::getOperation).containsExactly("RESET");
        assertThat(document().path("lastAction").path("result").path("completed").asInt()).isZero();
    }

    @Test
    void aLinkedScriptCannotBeReplacedByTheModel() throws Exception {
        prepare("AUTO");
        var doc = (tools.jackson.databind.node.ObjectNode) document();
        doc.set("parameterBindings", json.valueToTree(Map.of("steps", Map.of("source", "SECRET", "id", 9, "name", "Protected script"))));
        draft.update(json.writeValueAsString(doc));
        var result = steps("QUERY #replacement");
        assertThat(result.getFailed()).isTrue();
        assertThat(result.getOutput()).contains("read-only");
        assertThat(browserRequests).isEmpty();
        assertThat(document().path("settings").path("steps").asString()).isEqualTo("QUERY #username");
    }

    @Test
    void linkedValuesStayEphemeralAndContinuationRefusesAChangedSourceWithoutReResolvingIt() throws Exception {
        prepareReplay("", null);
        var plans = (WizardAlertPlanService) ReflectionTestUtils.getField(service, "alertPlans");
        var doc = (tools.jackson.databind.node.ObjectNode) document();
        ((tools.jackson.databind.node.ObjectNode) doc.path("settings")).put("url", "");
        doc.set("parameterBindings", json.valueToTree(Map.of("url", Map.of("source", "SECRET", "id", 9, "name", "Private URL", "version", 0))));
        draft.update(json.writeValueAsString(doc));
        String privateUrl = "https://fixture.invalid/private?token=synthetic-private-bound";
        when(plans.stamp(any())).thenReturn("version-1");
        when(plans.resolve(any(), any())).thenReturn(privateUrl);
        requestOperation("RESET");
        awaitOperation();
        requestOperation("EXECUTE");
        awaitOperation();
        assertThat(browserRequests).extracting(WizardRequest::getOperation).containsExactly("RESET", "EXECUTE");
        assertThat(json.readTree(browserRequests.getFirst().getJson()).path("url").asString()).isEqualTo(privateUrl);
        assertThat(draft.getDocument()).doesNotContain(privateUrl, "synthetic-private-bound");
        org.mockito.Mockito.verify(plans, org.mockito.Mockito.times(1)).resolve(any(), any());
        when(plans.stamp(any())).thenReturn("version-2");
        requestOperation("EXECUTE");
        awaitOperation();
        assertThat(browserRequests).hasSize(2);
        assertThat(document().path("lastAction").path("status").asString()).isEqualTo("FAILED");
        assertThat(ReflectionTestUtils.getField(session, "restartRequired")).isEqualTo(true);
        org.mockito.Mockito.verify(plans, org.mockito.Mockito.times(1)).resolve(any(), any());
    }

    @Test
    void parameterProceduresRequireSingleUseConfirmationAndDoNotRunDuringPreflightOrHeartbeat() throws Exception {
        prepareReplay("", null);
        var plans = (WizardAlertPlanService) ReflectionTestUtils.getField(service, "alertPlans");
        when(plans.stamp(any())).thenReturn("version-1");
        var doc = (tools.jackson.databind.node.ObjectNode) document();
        ((tools.jackson.databind.node.ObjectNode) doc.path("settings")).put("steps", "");
        doc.set("parameterBindings", json.valueToTree(Map.of("steps", Map.of("source", "PROCEDURE", "id", 7, "name", "Script provider", "version", 0))));
        draft.update(json.writeValueAsString(doc));
        var authentication = new UsernamePasswordAuthenticationToken("admin-subject", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        var request = json.createObjectNode().put("operation", "REPLAY").put("id", draft.getId().toString()).put("clientId", "browser-a");
        var confirmation = json.valueToTree(service.request(authentication, request));
        assertThat(confirmation.path("parameterConfirmation").path("procedures").get(0).path("parameter").asString()).isEqualTo("steps");
        var executions = (ProcedureExecutionOrchestrator) ReflectionTestUtils.getField(service, "procedureExecutions");
        verifyNoInteractions(executions);
        assertThat(browserRequests).isEmpty();
        when(executions.prepareWizardValue(any(), any(), any(), any(), any())).thenReturn("pv.fixture");
        when(executions.invokeToken("pv.fixture")).thenReturn(app.alertify.worker.grpc.InvokeProcedureResponse.newBuilder().setResult(app.alertify.worker.grpc.ProcedureInvocationResult.newBuilder().setResultJson(json.writeValueAsString("QUERY #username"))).build());
        request.put("parameterConfirmationToken", confirmation.path("parameterConfirmation").path("token").asString());
        service.request(authentication, request);
        awaitOperation();
        assertThat(document().path("lastAction").path("status").asString()).isEqualTo("SUCCESS");
        assertThat(document().path("settings").path("steps").asString()).isEmpty();
        org.mockito.Mockito.verify(executions, org.mockito.Mockito.times(1)).invokeToken("pv.fixture");
        requestOperation("HEARTBEAT");
        var repeated = json.valueToTree(service.request(authentication, request));
        assertThat(repeated.has("parameterConfirmation")).isTrue();
        org.mockito.Mockito.verify(executions, org.mockito.Mockito.times(1)).invokeToken("pv.fixture");
    }

    @SuppressWarnings("unchecked")
    private void prepareReplay(String failedOperation, CompletableFuture<WizardRequest> reset) throws Exception {
        prepare("ASK");
        ((Map<UUID, Object>) ReflectionTestUtils.getField(service, "sessions")).put(draft.getId(), session);
        ReflectionTestUtils.setField(session, "restartRequired", true);
        StreamObserver<WizardRequest> browser = mock(StreamObserver.class);
        doAnswer(invocation -> {
            WizardRequest request = invocation.getArgument(0);
            browserRequests.add(request);
            if (request.getOperation().equals("RESET") && reset != null) reset.complete(request);
            else completeBrowserRequest(request, failedOperation);
            return null;
        }).when(browser).onNext(any());
        ReflectionTestUtils.setField(session, "requests", browser);
    }

    @SuppressWarnings("unchecked")
    private void completeBrowserRequest(WizardRequest request, String failedOperation) {
        var pending = (ConcurrentMap<String, CompletableFuture<WizardEvent>>) ReflectionTestUtils.getField(session, "pending");
        var reply = pending.get(request.getRequestId());
        if (request.getOperation().equals(failedOperation)) reply.completeExceptionally(new IllegalArgumentException("Synthetic browser action failure"));
        else reply.complete(WizardEvent.newBuilder().setType("RESULT").setJson("{\"completed\":1,\"status\":\"READY\"}").build());
    }

    private void requestOperation(String operation) {
        var authentication = new UsernamePasswordAuthenticationToken("admin-subject", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        service.request(authentication, json.valueToTree(Map.of("operation", operation, "id", draft.getId(), "clientId", "browser-a")));
    }

    private void awaitOperation() {
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            while (((java.util.concurrent.atomic.AtomicBoolean) ReflectionTestUtils.getField(session, "busy")).get()) Thread.sleep(10);
        });
    }

    @SuppressWarnings("unchecked")
    private void prepare(String mode) throws Exception {
        selectorChecks.clear();
        selectorReport = json.valueToTree(Map.of("visibleCount", 1, "totalCount", 1, "ambiguous", false));
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
            var pending = (ConcurrentMap<String, CompletableFuture<WizardEvent>>) ReflectionTestUtils.getField(session, "pending");
            if (request.getOperation().equals("CHECK_SELECTOR")) {
                selectorChecks.add(json.readTree(request.getJson()).path("selector").asString());
                pending.get(request.getRequestId()).complete(WizardEvent.newBuilder().setType("RESULT").setJson(json.writeValueAsString(selectorReport)).build());
                return null;
            }

            browserRequests.add(request);
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
