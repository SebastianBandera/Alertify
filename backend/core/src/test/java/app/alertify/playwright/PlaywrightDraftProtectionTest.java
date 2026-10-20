package app.alertify.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class PlaywrightDraftProtectionTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CANARY = "fixture-private-credential";

    @ParameterizedTest
    @ValueSource(strings = {
            "QUERY [data-token=VALUE]", "QUERY [data-token=\"VALUE\"]", "QUERY button:has-text('VALUE')", "QUERY #VALUE",
            "WAIT_VISIBLE [data-token=VALUE] 10s", "WAIT_HIDDEN [data-token=VALUE]", "QUERY main\nCHECK TEXT VALUE",
            "QUERY main\nCHECK ATTRIBUTE data-state VALUE", "QUERY select\nSELECT VALUE", "CHECK URL **/VALUE",
            "# VALUE", "UNFINISHED VALUE" })
    void rejectsProtectedDataLiteralsWithoutEchoingOrRewritingThem(String template) {
        String steps = template.replace("VALUE", CANARY);
        JsonNode document = document(steps, "");
        String original = document.toString();
        assertThatThrownBy(() -> PlaywrightWizardService.protectedDraft(document, List.of(CANARY)))
                .isInstanceOf(PlaywrightDraftProtection.SensitiveContentFailure.class)
                .hasMessageContaining(PlaywrightDraftProtection.CODE).hasMessageNotContaining(CANARY);
        assertThat(document.toString()).isEqualTo(original);
    }

    @Test
    void rejectsCssEscapesBase64AndPendingProposals() {
        for (String representation : List.of(CANARY, Base64.getEncoder().encodeToString(CANARY.getBytes(StandardCharsets.UTF_8)), Base64.getUrlEncoder().withoutPadding().encodeToString(CANARY.getBytes(StandardCharsets.UTF_8)))) {
            assertThatThrownBy(() -> PlaywrightWizardService.protectedDraft(document("QUERY main", "QUERY [data-token=\"" + representation + "\"]"), List.of(CANARY)))
                    .isInstanceOf(PlaywrightDraftProtection.SensitiveContentFailure.class).hasMessageNotContaining(CANARY);
        }
        assertThatThrownBy(() -> PlaywrightWizardService.protectedDraft(document("QUERY #fixture-\\70 rivate-credential", ""), List.of(CANARY)))
                .isInstanceOf(PlaywrightDraftProtection.SensitiveContentFailure.class);
    }

    @Test
    void preservesShortValuesInReferencesNumericOperandsSelectorsAndRevisionMetadata() {
        String steps = "CALL PROCEDURE \"Procedure1\" POINTER \"/items/1\" AS code1\nQUERY #field1\nFILL vars.code1\nFILL SECRET \"Secret1\" POINTER \"/username\"\nCHECK COUNT = 1\nWAIT 1s\nWAIT_VISIBLE #field1 10s";
        var document = (tools.jackson.databind.node.ObjectNode) document(steps, "QUERY #field1\nCHECK COUNT >= 1");
        document.put("testedRevision", "ab1234").put("proposalRevision", "hash1").put("proposalClient", "browser1");
        var safe = PlaywrightWizardService.protectedDraft(document, List.of("1"));
        for (String field : List.of("settings", "proposal", "testedRevision", "proposalRevision", "proposalClient"))
            assertThat(safe.path(field)).isEqualTo(document.path(field));
    }

    @Test
    void stillRejectsShortValuesInActualDataLiterals() {
        for (String steps : List.of("QUERY [data-token=1]", "QUERY [data-token=\"1\"]", "QUERY main\nCHECK TEXT 1", "QUERY select\nSELECT 1", "QUERY #1", "QUERY #MQ", "QUERY text=1"))
            assertThatThrownBy(() -> PlaywrightWizardService.protectedDraft(document(steps, ""), List.of("1")))
                    .isInstanceOf(PlaywrightDraftProtection.SensitiveContentFailure.class);
    }

    @Test
    void rejectsUrlLiteralsButKeepsStructuralHostAndPortDigits() {
        var document = (tools.jackson.databind.node.ObjectNode) document("QUERY #field1", "");
        ((tools.jackson.databind.node.ObjectNode) document.path("settings")).put("url", "http://127.0.0.1:52198/");
        assertThat(PlaywrightWizardService.protectedDraft(document, List.of("1")).path("settings")).isEqualTo(document.path("settings"));
        ((tools.jackson.databind.node.ObjectNode) document.path("settings")).put("url", "https://fixture.test/session/" + CANARY);
        assertThatThrownBy(() -> PlaywrightWizardService.protectedDraft(document, List.of(CANARY)))
                .isInstanceOf(PlaywrightDraftProtection.SensitiveContentFailure.class).hasMessageNotContaining(CANARY);
    }

    @Test
    void providerContextRedactsLegacySettingsProposalsAndMessagesWithoutChangingTheStoredDocument() {
        var document = (tools.jackson.databind.node.ObjectNode) document("QUERY main\nCHECK TEXT " + CANARY, "QUERY [data-token=\"" + CANARY + "\"]");
        document.set("messages", JSON.valueToTree(List.of(Map.of("content", "Historical " + CANARY))));
        JsonNode safe = PlaywrightWizardService.modelDocument(document, List.of(CANARY));
        assertThat(safe.toString()).doesNotContain(CANARY).contains("[REDACTED]");
        assertThat(document.path("settings").path("steps").asString()).contains(CANARY);
    }

    private static JsonNode document(String steps, String proposal) {
        return JSON.valueToTree(Map.of("settings", Map.of("url", "https://fixture.test/", "steps", steps), "proposal", proposal));
    }
}
