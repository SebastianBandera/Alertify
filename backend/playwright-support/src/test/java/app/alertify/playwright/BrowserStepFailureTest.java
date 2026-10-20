package app.alertify.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;

class BrowserStepFailureTest {
    @Test
    void identifiesAnAmbiguousActionWithoutReturningTheRawDiagnostic() {
        var step = PlaywrightPageEngine.parseCommands("QUERY a\nUSE").getLast();
        var failure = new BrowserStepFailure(step, new PlaywrightException("strict mode violation: raw-private-value selector and remote body"));
        assertThat(failure.code).isEqualTo("AMBIGUOUS_SELECTOR");
        assertThat(failure.line).isEqualTo(2);
        assertThat(failure.command).isEqualTo("USE");
        assertThat(failure.getMessage()).contains("more than one element", "QUERY").doesNotContain("raw-private-value", "remote body");
    }

    @Test
    void identifiesATimeoutWithoutReturningTheRawDiagnostic() {
        var step = PlaywrightPageEngine.parseCommands("QUERY input").getFirst();
        var failure = new BrowserStepFailure(step, new TimeoutError("raw-private-value in timeout diagnostic"));
        assertThat(failure.code).isEqualTo("ELEMENT_TIMEOUT");
        assertThat(failure.getMessage()).contains("Line 1", "timeout").doesNotContain("raw-private-value");
    }
}
