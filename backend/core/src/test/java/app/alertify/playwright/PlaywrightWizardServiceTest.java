package app.alertify.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import app.alertify.codex.AiWorkerClient;
import app.alertify.grpc.WorkerGrpcChannelFactory;
import app.alertify.grpc.discovery.WorkerStatusService;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;
import app.alertify.jpa.repository.ProcedureRepository;
import app.alertify.procedures.execution.ProcedureExecutionOrchestrator;
import app.alertify.procedures.execution.ProcedureInvocationRegistry;
import app.alertify.realtime.AdminEventPublisher;
import tools.jackson.databind.json.JsonMapper;

class PlaywrightWizardServiceTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final WizardDraftStore store = mock(WizardDraftStore.class);
    private final PlaywrightValuePreparationService valuePreparation = mock(PlaywrightValuePreparationService.class);
    private final PlaywrightWizardService service = new PlaywrightWizardService(
            store, mock(WorkerStatusService.class), mock(WorkerGrpcChannelFactory.class), mock(AiWorkerClient.class),
            mock(AdminEventPublisher.class), mock(ApplicationConfigurationRepository.class), valuePreparation,
            mock(ProcedureExecutionOrchestrator.class), mock(ProcedureInvocationRegistry.class), mock(ProcedureRepository.class), mock(WizardAlertPlanService.class), json);

    @Test
    void doesNotReuseAStoredExecutionReceiptAsEvidenceForANewBrowserSession() {
        var admin = new UsernamePasswordAuthenticationToken("admin-subject", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        var settings = new PlaywrightWizardService.Settings("https://fixture.test/", "# note\nQUERY h1\nCHECK TEXT Ready", "chromium", List.of("chromium"), 10, 5, "ASK");
        var draft = new WizardDraft("admin-subject", json.writeValueAsString(Map.of("settings", settings, "messages", List.of(),
                "lastAction", Map.of("operation", "EXECUTE", "status", "SUCCESS", "result", Map.of("completed", 2)))));
        when(store.load("admin-subject", draft.getId())).thenReturn(draft);

        var state = json.valueToTree(service.request(admin, json.valueToTree(Map.of("operation", "GET", "id", draft.getId()))));

        assertThat(state.path("scriptExecution").path("status").asString()).isEqualTo("NO_SESSION");
        assertThat(state.path("scriptExecution").path("completed").asInt()).isZero();
        assertThat(state.path("scriptExecution").path("commandLines").valueStream().map(value -> value.asInt()).toList()).containsExactly(2, 3);
    }

    @Test
    void normalizesBareAddressesWithoutRewritingExplicitSchemesOrCredentials() {
        var cases = Map.ofEntries(
                Map.entry(" www.anep.edu.uy/path?q=1#section ", "https://www.anep.edu.uy/path?q=1#section"),
                Map.entry("//www.anep.edu.uy", "https://www.anep.edu.uy"),
                Map.entry("alertify.dev:8443/path", "https://alertify.dev:8443/path"),
                Map.entry("localhost:8080/", "https://localhost:8080/"),
                Map.entry("[::1]:8443/", "https://[::1]:8443/"),
                Map.entry("http://fixture.test/", "http://fixture.test/"),
                Map.entry("HTTPS://fixture.test/", "HTTPS://fixture.test/"),
                Map.entry("ftp://fixture.test/", "ftp://fixture.test/"),
                Map.entry("javascript:alert(1)", "javascript:alert(1)"),
                Map.entry("user:password@fixture.test", "user:password@fixture.test"),
                Map.entry("user@fixture.test", "https://user@fixture.test"),
                Map.entry(" ", ""));
        cases.forEach((input, expected) -> assertThat(PlaywrightWizardService.normalizeUrl(input)).isEqualTo(expected));
    }

    @Test
    void savesCanonicalUrlsAndPreparesOlderBareDomainDrafts() {
        var admin = new UsernamePasswordAuthenticationToken("admin-subject", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        var settings = new PlaywrightWizardService.Settings("www.anep.edu.uy", "", "chromium", List.of("chromium"), 10, 5, "ASK");
        var draft = new WizardDraft("admin-subject", json.writeValueAsString(Map.of("settings", settings, "messages", List.of())));
        when(store.load("admin-subject", draft.getId())).thenReturn(draft);
        service.request(admin, json.valueToTree(Map.of("operation", "SAVE", "id", draft.getId(), "clientId", "browser-a", "settings", settings)));
        var saved = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(store).save(org.mockito.ArgumentMatchers.eq("admin-subject"), org.mockito.ArgumentMatchers.eq(draft.getId()), saved.capture());
        assertThat(json.readTree(saved.getValue()).path("settings").path("url").asString()).isEqualTo("https://www.anep.edu.uy");
        var prepared = json.valueToTree(service.request(admin, json.valueToTree(Map.of("operation", "PREPARE", "id", draft.getId(), "clientId", "browser-a", "bypass", true))));
        assertThat(prepared.path("settings").path("url").asString()).isEqualTo("https://www.anep.edu.uy");
    }

    @Test
    void continuesInTheActualLastTestedBrowserWithoutChangingTheTestMatrixOrReplaying() {
        var settings = new PlaywrightWizardService.Settings("https://example.com/", "QUERY h1", "chromium", List.of("chromium", "firefox"), 10, 5, "ASK");
        var report = json.valueToTree(Map.of("activeEngine", "firefox", "passed", true));
        var settled = PlaywrightWizardService.settingsAfterTest(settings, report);
        assertThat(settled.engine()).isEqualTo("firefox");
        assertThat(settled.steps()).isEqualTo(settings.steps());
        assertThat(settled.browsers()).isEqualTo(settings.browsers());
        assertThat(PlaywrightWizardService.settingsAfterTest(settings, json.valueToTree(Map.of("passed", true)))).isEqualTo(settled);
    }

    @Test
    void doesNotInventAnActiveBrowserAfterAnOpeningFailureOrAcceptAnUnexpectedEngine() {
        var settings = new PlaywrightWizardService.Settings("https://example.com/", "", "chromium", List.of("firefox"), 10, 5, "ASK");
        assertThat(PlaywrightWizardService.settingsAfterTest(settings, json.valueToTree(Map.of("activeEngine", "", "passed", false)))).isNull();
        assertThatThrownBy(() -> PlaywrightWizardService.settingsAfterTest(settings, json.valueToTree(Map.of("activeEngine", "webkit")))).hasMessageContaining("unexpected");
    }

    @Test
    void shortJsonResultsDoNotCorruptDeclaredPointersRevisionHashesOrExecutionStatus() {
        var document = json.valueToTree(Map.of("settings", Map.of("steps", "CALL PROCEDURE \"Uno\" POINTER \"/items/1\" AS codigo"), "testedRevision", "ab1234", "messages", List.of(Map.of("content", "Remote value 1")), "lastAction", Map.of("operation", "EXECUTE", "status", "SUCCESS", "error", "Remote value 1")));
        var protectedDraft = PlaywrightWizardService.protectedDraft(document, List.of("1", "SUCCESS"));
        assertThat(protectedDraft.path("settings")).isEqualTo(document.path("settings"));
        assertThat(protectedDraft.path("testedRevision").asString()).isEqualTo("ab1234");
        assertThat(protectedDraft.path("lastAction").path("status").asString()).isEqualTo("SUCCESS");
        assertThat(protectedDraft.path("lastAction").path("error").asString()).isEqualTo("Remote value [REDACTED]");
        assertThat(protectedDraft.path("messages").get(0).path("content").asString()).isEqualTo("Remote value [REDACTED]");
    }

    @Test
    void rejectsOfflineSaveAndBypassPreparationUsingDeclaredSecretValues() {
        var admin = new UsernamePasswordAuthenticationToken("admin-subject", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        String canary = "fixture-private-offline";
        String steps = "QUERY #password\nFILL secrets.Fixture\nQUERY main\nCHECK TEXT " + canary;
        var reference = new PlaywrightPageEngine.ValueReference(app.alertify.alerts.AlertExecutionValueSource.SECRET, "Fixture");
        when(valuePreparation.resolve(org.mockito.ArgumentMatchers.eq(reference), org.mockito.ArgumentMatchers.any())).thenReturn(new app.alertify.alerts.AlertExecutionValue(reference.source(), reference.key(), canary));
        var clean = new PlaywrightWizardService.Settings("https://fixture.test/", "QUERY #password\nFILL secrets.Fixture", "chromium", List.of("chromium"), 10, 5, "ASK");
        var contaminated = clean.withSteps(steps);
        var draft = new WizardDraft("admin-subject", json.writeValueAsString(Map.of("settings", clean, "messages", List.of())));
        when(store.load("admin-subject", draft.getId())).thenReturn(draft);
        var payload = json.valueToTree(Map.of("operation", "SAVE", "id", draft.getId(), "clientId", "browser-a", "settings", contaminated));
        assertThatThrownBy(() -> service.request(admin, payload)).isInstanceOf(PlaywrightDraftProtection.SensitiveContentFailure.class).hasMessageNotContaining(canary);
        org.mockito.Mockito.verify(store, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        draft.update(json.writeValueAsString(Map.of("settings", contaminated, "messages", List.of())));
        var bypass = json.valueToTree(Map.of("operation", "PREPARE", "id", draft.getId(), "clientId", "browser-a", "bypass", true));
        assertThatThrownBy(() -> service.request(admin, bypass)).isInstanceOf(PlaywrightDraftProtection.SensitiveContentFailure.class).hasMessageNotContaining(canary);
    }

    @Test
    void removingAFillReferenceCannotSmuggleItsKnownValueIntoTheReplacementScript() {
        var admin = new UsernamePasswordAuthenticationToken("admin-subject", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        String canary = "fixture-removed-reference";
        var reference = new PlaywrightPageEngine.ValueReference(app.alertify.alerts.AlertExecutionValueSource.SECRET, "Fixture");
        when(valuePreparation.resolve(org.mockito.ArgumentMatchers.eq(reference), org.mockito.ArgumentMatchers.any())).thenReturn(new app.alertify.alerts.AlertExecutionValue(reference.source(), reference.key(), canary));
        var previous = new PlaywrightWizardService.Settings("https://fixture.test/", "QUERY #password\nFILL secrets.Fixture", "chromium", List.of("chromium"), 10, 5, "ASK");
        var draft = new WizardDraft("admin-subject", json.writeValueAsString(Map.of("settings", previous, "messages", List.of())));
        when(store.load("admin-subject", draft.getId())).thenReturn(draft);
        var payload = json.valueToTree(Map.of("operation", "SAVE", "id", draft.getId(), "clientId", "browser-a", "settings", previous.withSteps("QUERY main\nCHECK TEXT " + canary)));
        assertThatThrownBy(() -> service.request(admin, payload)).isInstanceOf(PlaywrightDraftProtection.SensitiveContentFailure.class);
        org.mockito.Mockito.verify(store, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void blocksAnUnselectedProcedureEvenWhenBypassingTheBrowserTest() {
        var admin = new UsernamePasswordAuthenticationToken("admin-subject", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        var procedure = mock(app.alertify.procedures.model.Procedure.class);
        when(procedure.getId()).thenReturn(7L);
        when(valuePreparation.procedure("Generar OTP")).thenReturn(procedure);
        var settings = new PlaywrightWizardService.Settings("https://example.com/", "CALL PROCEDURE \"Generar OTP\" POINTER \"/code\" AS codigo", "chromium", List.of("chromium"), 10, 5, "ASK");
        var draft = new WizardDraft("admin-subject", json.writeValueAsString(Map.of("settings", settings, "messages", List.of(), "allowedProcedureIds", List.of())));
        when(store.load("admin-subject", draft.getId())).thenReturn(draft);
        var payload = json.valueToTree(Map.of("operation", "PREPARE", "id", draft.getId(), "clientId", "browser-a", "bypass", true));
        assertThatThrownBy(() -> service.request(admin, payload)).hasMessageContaining("not enabled for this assistant");
    }

    @Test
    void requiresAdministratorAccessBeforeReadingADraft() {
        var viewer = new UsernamePasswordAuthenticationToken("viewer", "", List.of(new SimpleGrantedAuthority("ROLE_DASHBOARD")));
        assertThatThrownBy(() -> service.request(viewer, json.valueToTree(Map.of("operation", "LIST"))))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void blocksCreationWithoutACurrentPassingTestAndAllowsTheExplicitException() {
        var admin = new UsernamePasswordAuthenticationToken("admin-subject", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        var settings = new PlaywrightWizardService.Settings("https://example.com/", "QUERY h1\nCHECK TEXT Example", "chromium", List.of("chromium"), 10, 5, "ASK");
        var draft = new WizardDraft("admin-subject", json.writeValueAsString(Map.of("settings", settings, "messages", List.of())));
        when(store.load("admin-subject", draft.getId())).thenReturn(draft);
        var payload = json.valueToTree(Map.of("operation", "PREPARE", "id", draft.getId(), "clientId", "browser-a"));
        assertThatThrownBy(() -> service.request(admin, payload)).hasMessageContaining("successful test");
        var bypass = json.valueToTree(Map.of("operation", "PREPARE", "id", draft.getId(), "clientId", "browser-a", "bypass", true));
        assertThat(((Map<?, ?>) service.request(admin, bypass)).containsKey("settings")).isTrue();
    }

    @Test
    void savingUnchangedSettingsPreservesThePendingProposalAndSuccessfulTest() {
        var admin = new UsernamePasswordAuthenticationToken("admin-subject", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        var settings = new PlaywrightWizardService.Settings("https://example.com/", "QUERY h1", "chromium", List.of("chromium"), 10, 5, "ASK");
        String testedRevision = org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "revision", settings);
        var document = json.valueToTree(Map.of("settings", settings, "messages", List.of(), "proposal", "QUERY #menu\nHOVER", "proposalRevision", "original-revision", "proposalClient", "browser-a", "testedRevision", testedRevision,
                "testReport", Map.of("passed", true, "activeEngine", "chromium", "browsers", List.of(Map.of("browser", "chromium", "passed", true, "completed", 1)))));
        var draft = new WizardDraft("admin-subject", json.writeValueAsString(document));
        when(store.load("admin-subject", draft.getId())).thenReturn(draft);
        var payload = json.createObjectNode().put("operation", "SAVE").put("id", draft.getId().toString()).put("clientId", "browser-a");
        payload.set("settings", json.valueToTree(settings));
        service.request(admin, payload);
        var saved = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(store).save(org.mockito.ArgumentMatchers.eq("admin-subject"), org.mockito.ArgumentMatchers.eq(draft.getId()), saved.capture());
        var updated = (tools.jackson.databind.node.ObjectNode) json.readTree(saved.getValue());
        assertThat(updated.path("testValidation").path("results").path("chromium").path("passed").asBoolean()).isTrue();
        updated.remove("testValidation");
        assertThat(updated).isEqualTo(document);
    }

    @Test
    void changingSettingsInvalidatesTheProposalAndItsApprovalMetadata() {
        var admin = new UsernamePasswordAuthenticationToken("admin-subject", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        var settings = new PlaywrightWizardService.Settings("https://example.com/", "QUERY h1", "chromium", List.of("chromium"), 10, 5, "ASK");
        var document = json.valueToTree(Map.of("settings", settings, "messages", List.of(), "proposal", "QUERY #menu\nHOVER", "proposalRevision", "original-revision", "proposalClient", "browser-a", "testedRevision", "passing-revision"));
        var draft = new WizardDraft("admin-subject", json.writeValueAsString(document));
        when(store.load("admin-subject", draft.getId())).thenReturn(draft);
        var payload = json.createObjectNode().put("operation", "SAVE").put("id", draft.getId().toString()).put("clientId", "browser-a");
        payload.set("settings", json.valueToTree(new PlaywrightWizardService.Settings(settings.url(), settings.steps(), "firefox", settings.browsers(), 10, 5, "ASK")));
        service.request(admin, payload);
        var saved = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(store).save(org.mockito.ArgumentMatchers.eq("admin-subject"), org.mockito.ArgumentMatchers.eq(draft.getId()), saved.capture());
        var updated = json.readTree(saved.getValue());
        assertThat(updated.has("proposal")).isFalse();
        assertThat(updated.has("proposalRevision")).isFalse();
        assertThat(updated.has("proposalClient")).isFalse();
        assertThat(updated.has("testedRevision")).isFalse();
    }

    @Test
    void cleanTestEvidenceTracksExecutionInputsButSurvivesInteractiveAndBrowserSelectionChanges() {
        var plans = mock(WizardAlertPlanService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "alertPlans", plans);
        when(plans.stamp(org.mockito.ArgumentMatchers.any())).thenReturn("source-v1");
        var settings = new PlaywrightWizardService.Settings("https://fixture.test/", "QUERY h1", "chromium", List.of("chromium"), 10, 5, "ASK");
        var original = json.createObjectNode();
        original.set("settings", json.valueToTree(settings));
        original.set("parameterBindings", json.valueToTree(Map.of("url", Map.of("source", "CONFIG", "id", 1))));
        original.set("allowedProcedureIds", json.valueToTree(List.of(7L)));
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "refreshValidation", original);
        WizardTestValidation.record((tools.jackson.databind.node.ObjectNode) original.path("testValidation"), json.valueToTree(Map.of("browsers", List.of(Map.of("browser", "chromium", "passed", true, "completed", 1)))));
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "refreshValidation", original);
        assertThat(original.has("testedRevision")).isTrue();

        var selection = original.deepCopy();
        selection.set("settings", json.valueToTree(new PlaywrightWizardService.Settings(settings.url(), settings.steps(), "firefox", List.of("chromium", "webkit"), 10, 5, "AUTO")));
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "refreshValidation", selection);
        assertThat(selection.path("testValidation")).isEqualTo(original.path("testValidation"));
        assertThat(selection.has("testedRevision")).isFalse();

        for (var changed : List.of(
                new PlaywrightWizardService.Settings("https://other.test/", settings.steps(), "chromium", settings.browsers(), 10, 5, "ASK"),
                settings.withSteps("QUERY h2"),
                new PlaywrightWizardService.Settings(settings.url(), settings.steps(), "chromium", settings.browsers(), 11, 5, "ASK"),
                new PlaywrightWizardService.Settings(settings.url(), settings.steps(), "chromium", settings.browsers(), 10, 6, "ASK"))) {
            var document = original.deepCopy();
            document.set("settings", json.valueToTree(changed));
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "refreshValidation", document);
            assertThat(document.path("testValidation").path("results").isEmpty()).isTrue();
            assertThat(document.has("testedRevision")).isFalse();
        }
        var permissionChange = original.deepCopy();
        permissionChange.set("allowedProcedureIds", json.valueToTree(List.of(8L)));
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "refreshValidation", permissionChange);
        assertThat(permissionChange.path("testValidation").path("results").isEmpty()).isTrue();
        when(plans.stamp(org.mockito.ArgumentMatchers.any())).thenReturn("source-v2");
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "refreshValidation", original);
        assertThat(original.path("testValidation").path("results").isEmpty()).isTrue();
        assertThat(original.has("testedRevision")).isFalse();
    }

    @Test
    void savingClientSettingsNeverAcceptsAForgedPassingTest() {
        var admin = new UsernamePasswordAuthenticationToken("admin-subject", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        var settings = new PlaywrightWizardService.Settings("https://example.com/", "", "chromium", List.of("chromium"), 10, 5, "ASK");
        var draft = new WizardDraft("admin-subject", json.writeValueAsString(Map.of("settings", settings, "messages", List.of())));
        when(store.load("admin-subject", draft.getId())).thenReturn(draft);
        var settingsNode = json.valueToTree(settings);
        var payload = json.createObjectNode().put("operation", "SAVE").put("id", draft.getId().toString()).put("clientId", "browser-a").put("testedRevision", "forged");
        payload.set("settings", settingsNode);
        payload.set("testValidation", json.valueToTree(Map.of("revision", "forged", "results", Map.of("chromium", Map.of("passed", true)))));
        service.request(admin, payload);
        org.mockito.Mockito.verify(store).save(org.mockito.ArgumentMatchers.eq("admin-subject"), org.mockito.ArgumentMatchers.eq(draft.getId()), org.mockito.ArgumentMatchers.argThat(value -> !value.contains("forged")));
    }

    @Test
    void recoversAnOlderDraftWithAnUnrecordedFailureWithoutCallingItsPreviousSuccessCurrent() {
        var admin = new UsernamePasswordAuthenticationToken("admin-subject", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        var draft = new WizardDraft("admin-subject", json.writeValueAsString(Map.of("settings", Map.of(), "messages", List.of(), "error", "Restart after a failed action", "lastAction", Map.of("operation", "EXECUTE", "result", Map.of("completed", 2, "status", "READY")))));
        when(store.load("admin-subject", draft.getId())).thenReturn(draft);
        var response = json.valueToTree(service.request(admin, json.valueToTree(Map.of("operation", "GET", "id", draft.getId()))));
        assertThat(response.path("document").path("lastAction").path("status").asString()).isEqualTo("FAILED");
        assertThat(response.path("restartRequired").asBoolean()).isTrue();
        org.mockito.Mockito.verify(store, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }
}
