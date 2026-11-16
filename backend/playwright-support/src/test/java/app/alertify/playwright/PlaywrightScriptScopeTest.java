package app.alertify.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PlaywrightScriptScopeTest {
    @Test
    void keepsPhysicalLinesAndTheEntireCommandListForPartialExecution() {
        String script = "# Login\r\nQUERY input\r\nFILL secrets.First\r\n\r\n# Later\r\nFILL secrets.Second\r\n";
        var partial = PlaywrightPageEngine.scriptScope(script, 4);
        assertThat(partial.scope()).isEqualTo("PARTIAL");
        assertThat(partial.lineCount()).isEqualTo(7);
        assertThat(partial.commands()).extracting(PlaywrightPageEngine.Command::lineNumber).containsExactly(2, 3, 6);
        assertThat(PlaywrightPageEngine.requiredValues(script, 4)).extracting(PlaywrightPageEngine.ValueReference::name).containsExactly("First");
        assertThat(PlaywrightPageEngine.requiredValues(script)).extracting(PlaywrightPageEngine.ValueReference::name).containsExactly("First", "Second");
        assertThat(PlaywrightPageEngine.scriptScope(script, 7).scope()).isEqualTo("FULL");
        assertThat(PlaywrightPageEngine.scriptScope(script, null).untilLine()).isNull();
    }

    @Test
    void validatesRangesIncludingEmptyScriptsAndDoesNotSplitConfirmationPairs() {
        assertThat(PlaywrightPageEngine.scriptScope("", 1).scope()).isEqualTo("FULL");
        assertThat(PlaywrightPageEngine.scriptScope(null, null).lineCount()).isEqualTo(1);
        for (int line : new int[] { 0, -1, 2, Integer.MAX_VALUE, Integer.MIN_VALUE })
            assertThatThrownBy(() -> PlaywrightPageEngine.scriptScope("", line)).hasMessageContaining("physical script line");

        String script = "CONFIRM ACCEPT \"Confirm?\"\n# Activate\n\nCLICK 10 10\n# End";
        for (int line : new int[] { 1, 2, 3 })
            assertThatThrownBy(() -> PlaywrightPageEngine.scriptScope(script, line)).hasMessageContaining("cannot separate");

        assertThat(PlaywrightPageEngine.scriptScope(script, 4).scope()).isEqualTo("PARTIAL");
        assertThat(PlaywrightPageEngine.scriptScope(script, 5).scope()).isEqualTo("FULL");
    }
}
