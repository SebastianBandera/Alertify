package app.alertify.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
            "# VALUE", "CONFIRM ACCEPT \"VALUE\"\nCLICK 10 10", "UNFINISHED VALUE" })
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
    void rejectsDecodedSensitiveConfirmationLiteralsAndPendingProposals() {
        for (String message : List.of(CANARY, "fixture-\\u0070rivate-credential")) {
            String confirmation = "CONFIRM ACCEPT \"" + message + "\"\nCLICK 10 10";
            assertThatThrownBy(() -> PlaywrightWizardService.protectedDraft(document(confirmation, ""), List.of(CANARY)))
                    .isInstanceOf(PlaywrightDraftProtection.SensitiveContentFailure.class).hasMessageNotContaining(CANARY);
            assertThatThrownBy(() -> PlaywrightWizardService.protectedDraft(document("QUERY button", confirmation), List.of(CANARY)))
                    .isInstanceOf(PlaywrightDraftProtection.SensitiveContentFailure.class).hasMessageNotContaining(CANARY);
        }
    }

    @Test
    void preservesShortValuesInReferencesNumericOperandsSelectorsAndRevisionMetadata() {
        String steps = "CALL PROCEDURE \"Procedure1\" POINTER \"/items/1\" AS code1\nQUERY #field1\nFILL vars.code1\nFILL SECRET \"Secret1\" POINTER \"/username\"\nCHECK COUNT = 1\nWAIT 1s\nWAIT_VISIBLE #field1 10s\nCLICK 1 1%";
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

    @ParameterizedTest
    @ValueSource(ints = { 20_000, 100_000 })
    void acceptsLongQuotedSelectorsIncludingEscapedDelimitersWithoutGrowingTheStack(int length) {
        for (String quote : List.of("\"", "'")) {
            String escapedQuote = "\\" + quote;
            String literal = "x".repeat(length / 2 - 100) + escapedQuote.repeat(length / 4) + " Unicode ñ 🔒";
            String script = "QUERY [data-note=" + quote + literal + quote + "]";
            assertThatCode(() -> PlaywrightWizardService.protectedDraft(document(script, ""), List.of(CANARY))).doesNotThrowAnyException();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = { 20_000, 100_000 })
    void rejectsProtectedSuffixesInLongQuotedSelectorsWithoutEchoingThem(int length) {
        for (String quote : List.of("\"", "'")) {
            String script = "QUERY [data-note=" + quote + "x".repeat(length - 100) + CANARY + quote + "]";
            assertRejected(script, List.of(CANARY));
        }
    }

    @Test
    void keepsJsonAndCssUnicodeEscapeProtectionInLongLiterals() {
        String unicode = "clave-ñ-🔒";
        String prefix = "x".repeat(20_000);
        for (String script : List.of(
                "QUERY [data-note=\"" + prefix + "fixture-\\u0070rivate-credential\"]",
                "QUERY [data-note='" + prefix + "fixture-\\70 rivate-credential']",
                "QUERY [data-note=\"" + prefix + "clave-\\u00f1-\\ud83d\\udd12\"]",
                "QUERY [data-note='" + prefix + "clave-\\f1 -\\1f512 ']"))
            assertRejected(script, List.of(CANARY, unicode));
    }

    @Test
    void inspectsCompleteInnerLiteralsWhenAnOuterQuoteIsUnfinished() {
        String prefix = "x".repeat(20_000);
        assertRejected("QUERY outer' [data-note=\"" + prefix + "fixture-\\u0070rivate-credential\"]", List.of(CANARY));
        assertRejected("QUERY outer\" button:has-text('" + prefix + "fixture-\\70 rivate-credential')", List.of(CANARY));
    }

    @Test
    void preservesProtectionAndAcceptanceForLongUnfinishedQuotes() {
        String prefix = "x".repeat(99_800);
        for (String quote : List.of("\"", "'")) {
            for (String ending : List.of("", "\\")) {
                String safe = "QUERY [data-note=" + quote + prefix + "unfinished" + ending;
                assertThatCode(() -> PlaywrightWizardService.protectedDraft(document(safe, ""), List.of(CANARY))).doesNotThrowAnyException();
                assertRejected("QUERY [data-note=" + quote + prefix + CANARY + ending, List.of(CANARY));
            }
        }
    }

    @Test
    void scansManyEscapedQuoteCandidatesWithoutRepeatedSuffixSearches() {
        String script = "QUERY [data-note=\"" + "\\\"".repeat(49_900) + "unfinished";
        assertThatCode(() -> PlaywrightWizardService.protectedDraft(document(script, ""), List.of(CANARY))).doesNotThrowAnyException();
        assertRejected(script + CANARY, List.of(CANARY));
    }

    private static void assertRejected(String script, List<String> sensitive) {
        JsonNode original = document(script, "");
        String unchanged = original.toString();
        assertThatThrownBy(() -> PlaywrightWizardService.protectedDraft(original, sensitive))
                .isInstanceOf(PlaywrightDraftProtection.SensitiveContentFailure.class)
                .hasMessageContaining(PlaywrightDraftProtection.CODE)
                .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain(sensitive.toArray(String[]::new)));
        assertThat(original.toString()).isEqualTo(unchanged);
    }

    private static JsonNode document(String steps, String proposal) {
        return JSON.valueToTree(Map.of("settings", Map.of("url", "https://fixture.test/", "steps", steps), "proposal", proposal));
    }
}
