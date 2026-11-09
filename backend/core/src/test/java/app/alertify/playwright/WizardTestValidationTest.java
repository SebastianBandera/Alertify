package app.alertify.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class WizardTestValidationTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void accumulatesOnlyTheLatestEvidenceForEachEngineAndResetsOnRevisionChanges() {
        ObjectNode doc = json.createObjectNode();
        var validation = WizardTestValidation.current(doc, "revision-a", "legacy", WizardTestValidation.ENGINES);
        for (String engine : WizardTestValidation.ENGINES) {
            WizardTestValidation.record(validation, report(engine, true));
            assertThat(WizardTestValidation.passed(validation, List.of(engine))).isTrue();
        }
        assertThat(WizardTestValidation.passed(validation, WizardTestValidation.ENGINES)).isTrue();
        WizardTestValidation.invalidate(validation, List.of("firefox"));
        assertThat(WizardTestValidation.passed(validation, WizardTestValidation.ENGINES)).isFalse();
        assertThat(WizardTestValidation.passed(validation, List.of("chromium", "webkit"))).isTrue();
        WizardTestValidation.record(validation, report("firefox", false));
        assertThat(validation.path("results").path("firefox").path("passed").asBoolean()).isFalse();
        doc.set("testValidation", validation);
        assertThat(WizardTestValidation.current(doc, "revision-a", "legacy", List.of("webkit")).path("results").size()).isEqualTo(3);
        assertThat(WizardTestValidation.current(doc, "revision-b", "legacy", WizardTestValidation.ENGINES).path("results").isEmpty()).isTrue();
    }

    @Test
    void migratesOnlyAVerifiableCompleteLegacySuccessAndNeverResurrectsInvalidatedEvidence() {
        ObjectNode doc = json.createObjectNode().put("testedRevision", "old-revision");
        doc.set("testReport", report("chromium", true));
        assertThat(WizardTestValidation.current(doc, "new", "old-revision", List.of("chromium")).path("results").size()).isEqualTo(1);
        assertThat(WizardTestValidation.current(doc, "new", "changed", List.of("chromium")).path("results").isEmpty()).isTrue();
        assertThat(WizardTestValidation.current(doc, "new", "old-revision", WizardTestValidation.ENGINES).path("results").isEmpty()).isTrue();
        var validation = WizardTestValidation.current(doc, "new", "old-revision", List.of("chromium"));
        WizardTestValidation.invalidate(validation, List.of("chromium"));
        doc.set("testValidation", validation);
        assertThat(WizardTestValidation.current(doc, "new", "old-revision", List.of("chromium")).path("results").isEmpty()).isTrue();
        doc.set("testReport", report("chromium", false));
        doc.remove("testValidation");
        assertThat(WizardTestValidation.current(doc, "new", "old-revision", List.of("chromium")).path("results").isEmpty()).isTrue();
    }

    @Test
    void rejectsUnexpectedDuplicateMissingOrInconsistentBrowserReports() {
        assertThatThrownBy(() -> WizardTestValidation.validateReport(report("webkit", true), List.of("firefox"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WizardTestValidation.validateReport(report("firefox", true), WizardTestValidation.ENGINES)).isInstanceOf(IllegalArgumentException.class);
        ObjectNode inconsistent = (ObjectNode) report("firefox", false);
        inconsistent.put("passed", true);
        assertThatThrownBy(() -> WizardTestValidation.validateReport(inconsistent, List.of("firefox"))).isInstanceOf(IllegalArgumentException.class);
        ObjectNode duplicate = (ObjectNode) report("firefox", true);
        ((tools.jackson.databind.node.ArrayNode) duplicate.path("browsers")).add(duplicate.path("browsers").get(0).deepCopy());
        assertThatThrownBy(() -> WizardTestValidation.validateReport(duplicate, List.of("firefox"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WizardTestValidation.validateReport(json.createObjectNode(), List.of("firefox"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void transportFailureRevokesOnlyTargetedEnginesAndProtectsErrorTextWithoutCorruptingMetadata() {
        ObjectNode doc = json.createObjectNode();
        var validation = WizardTestValidation.current(doc, "revision", "legacy", WizardTestValidation.ENGINES);
        WizardTestValidation.record(validation, report("chromium", true));
        WizardTestValidation.fail(validation, List.of("firefox"));
        doc.set("testValidation", validation);
        ObjectNode testReport = (ObjectNode) report("firefox", false);
        ((ObjectNode) testReport.path("browsers").get(0)).put("error", "fixture-private-canary");
        testReport.set("requestedBrowsers", json.valueToTree(List.of("firefox")));
        doc.set("testReport", testReport);
        ((ObjectNode) validation.path("results").path("firefox")).put("error", "fixture-private-canary");
        JsonNode safe = PlaywrightWizardService.protectedDraft(doc, List.of("firefox", "revision", "fixture-private-canary"));
        assertThat(safe.path("testValidation").path("revision").asString()).isEqualTo("revision");
        assertThat(safe.path("testValidation").path("results").path("firefox").path("passed").asBoolean()).isFalse();
        assertThat(safe.path("testValidation").path("results").path("chromium").path("passed").asBoolean()).isTrue();
        assertThat(safe.path("testReport").path("browsers").get(0).path("browser").asString()).isEqualTo("firefox");
        assertThat(json.writeValueAsString(safe)).doesNotContain("fixture-private-canary");
    }

    private JsonNode report(String engine, boolean passed) {
        return json.valueToTree(Map.of("passed", passed, "browsers", List.of(Map.of("browser", engine, "passed", passed, "completed", 1))));
    }
}
