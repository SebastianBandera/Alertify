package app.alertify.playwright;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class WizardScriptProgressTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final WizardScriptProgress progress = new WizardScriptProgress();
    private final String script = "# comment\n\nQUERY h1\nCHECK TEXT Ready\n\nWAIT_VISIBLE #next 10s";

    @Test
    void tracksCommandsByPhysicalLineAndSeparatesTheRunningOrFailedCommand() {
        progress.reset(script, "chromium");
        assertThat(snapshot(script).commandLines()).containsExactly(3, 4, 6);
        event("STARTED", "chromium", 0, 0);
        event("RUNNING", "chromium", 0, 3);
        assertThat(snapshot(script).activeLine()).isEqualTo(3);
        event("COMPLETED", "chromium", 1, 3);
        event("RUNNING", "chromium", 1, 4);
        event("FAILED", "chromium", 1, 4);
        var failed = snapshot(script);
        assertThat(failed.completed()).isEqualTo(1);
        assertThat(failed.activeLine()).isNull();
        assertThat(failed.failedLine()).isEqualTo(4);
        assertThat(failed.status()).isEqualTo("FAILED");
    }

    @Test
    void retainsOnlyAConfirmedUnchangedPrefixWhenAppendingOrEditingPendingCommands() {
        progress.begin(script);
        event("STARTED", "chromium", 0, 0);
        event("COMPLETED", "chromium", 1, 3);
        var appended = snapshot(script + "\nQUERY #next");
        assertThat(appended.completed()).isEqualTo(1);
        assertThat(appended.commandLines()).containsExactly(3, 4, 6, 7);
        assertThat(snapshot(script.replace("Ready", "Changed pending check")).completed()).isEqualTo(1);
        assertThat(snapshot(script.replace("h1", "h2")).status()).isEqualTo("UNVERIFIED");
        assertThat(snapshot("QUERY h1").status()).isEqualTo("UNVERIFIED");
        assertThat(snapshot(script.replace("\n", "\r\n")).completed()).isEqualTo(1);
    }

    @Test
    void neverAppliesAnOldFailureToAnEditedCommandOrAnUnrelatedBrowserConfiguration() {
        progress.begin(script);
        event("STARTED", "chromium", 1, 0);
        event("FAILED", "chromium", 1, 4);
        assertThat(snapshot(script.replace("Ready", "Corrected")).status()).isEqualTo("UNVERIFIED");
        assertThat(progress.snapshot(script, false, false).status()).isEqualTo("UNVERIFIED");
        assertThat(snapshot(script + "\nQUERY #next").failedLine()).isEqualTo(4);
    }

    @Test
    void resetsBetweenBrowsersAndDoesNotTreatAnInterruptedActionAsNotExecuted() {
        progress.begin(script);
        event("STARTED", "chromium", 3, 0);
        event("OPENING", "firefox", 0, 0);
        assertThat(snapshot(script).status()).isEqualTo("UNVERIFIED");
        event("STARTED", "firefox", 0, 0);
        event("RUNNING", "firefox", 0, 3);
        assertThat(snapshot(script).completed()).isZero();
        assertThat(snapshot(script).engine()).isEqualTo("firefox");
        progress.interrupted();
        assertThat(snapshot(script).status()).isEqualTo("UNVERIFIED");
        progress.reset(script, "webkit");
        assertThat(snapshot(script).completed()).isZero();
        assertThat(snapshot(script).activeLine()).isNull();
        assertThat(snapshot(script).engine()).isEqualTo("webkit");
    }

    @Test
    void exposesOnlyMetadataAndHandlesMissingSessionsLinkedScriptsAndInvalidDrafts() {
        String privateScript = "QUERY input\nCHECK ATTRIBUTE data-private fixture-private-canary";
        progress.reset(privateScript, "chromium");
        String encoded = json.writeValueAsString(snapshot(privateScript));
        assertThat(encoded).doesNotContain("fixture-private-canary", "QUERY", "CHECK");
        assertThat(WizardScriptProgress.inactive(privateScript, false).completed()).isZero();
        assertThat(WizardScriptProgress.inactive(privateScript, false).status()).isEqualTo("NO_SESSION");
        assertThat(WizardScriptProgress.inactive(privateScript, true).commandLines()).isEmpty();
        assertThat(WizardScriptProgress.inactive("BROKEN", false).status()).isEqualTo("UNVERIFIED");
        progress.reset("BROKEN", "chromium");
        assertThat(snapshot("BROKEN").status()).isEqualTo("UNVERIFIED");
    }

    private WizardScriptProgress.Snapshot snapshot(String script) {
        return progress.snapshot(script, false, true);
    }

    private void event(String phase, String engine, int completed, int line) {
        progress.accept(json.valueToTree(Map.of("phase", phase, "engine", engine, "completed", completed, "line", line, "commandLines", List.of(3, 4, 6))));
    }
}
