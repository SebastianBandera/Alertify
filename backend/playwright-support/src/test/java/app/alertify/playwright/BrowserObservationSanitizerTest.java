package app.alertify.playwright;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class BrowserObservationSanitizerTest {
    @Test
    void protectsEscapedAccessibleNamesBeforeTheyCrossTheBrowserBoundary() {
        String value = "canary\"with\\characters";
        String snapshot = "- heading \"canary\\\"with\\\\characters\" [level=1] [ref=e7] [box=0,0,100,20]";
        assertThat(BrowserObservationSanitizer.sanitize(snapshot, List.of(value)))
                .isEqualTo("- heading \"[REDACTED]\" [level=1] [ref=e7] [box=0,0,100,20]");
    }

    @Test
    void protectsWhitespaceNormalizationAndYamlQuotedValues() {
        assertThat(BrowserObservationSanitizer.sanitize("- text: canary line next", List.of("canary\r\nline\t next"))).contains("[REDACTED]").doesNotContain("canary");
        assertThat(BrowserObservationSanitizer.sanitize("- text: 'canary''quoted'", List.of("canary'quoted"))).contains("[REDACTED]").doesNotContain("canary");
        assertThat(BrowserObservationSanitizer.sanitize("- heading \"Public content\"", List.of())).isEqualTo("- heading \"Public content\"");
    }
}
