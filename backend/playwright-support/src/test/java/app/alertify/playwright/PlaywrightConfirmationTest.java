package app.alertify.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import app.alertify.alerts.AlertExecutionContext;

class PlaywrightConfirmationTest {
    @Test
    void parsesExactMessagesAndPreservesSelectionAcrossCommentsAndBlankLines() {
        var commands = PlaywrightPageEngine.parseCommands("QUERY button\nCONFIRM ACCEPT \" ¿Cerrar la sesión? \\\"Sí\\\" \"\n# Cerrar sesión\n\nUSE\nCHECK TEXT Ready");
        assertThat(commands).hasSize(4);
        assertThat(commands.get(1).first()).isEqualTo(" ¿Cerrar la sesión? \"Sí\" ");
        assertThat(commands.get(1).keyword()).isEqualTo("CONFIRM");
        assertThat(commands.get(2).lineNumber()).isEqualTo(5);
        assertThat(PlaywrightPageEngine.requiredValues("CONFIRM ACCEPT \"Confirm?\"\nCLICK 10 10")).isEmpty();
        assertThat(PlaywrightPageEngine.parseCommands("CONFIRM ACCEPT \"\"\nCLICK 10 10")).hasSize(2);
    }

    @Test
    void rejectsUnsupportedSyntaxAndNonAdjacentOrMissingActivations() {
        for (String invalid : List.of("CONFIRM", "CONFIRM ACCEPT", "CONFIRM DISMISS \"text\"", "CONFIRM ACCEPT text", "CONFIRM ACCEPT /text/", "CONFIRM ACCEPT \"text\" extra", "CONFIRM ACCEPT 'text'"))
            assertThatThrownBy(() -> PlaywrightPageEngine.parseCommands(invalid + "\nCLICK 1 1")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Line 1");

        for (String next : List.of("QUERY button", "WAIT 1s", "RELOAD", "DOUBLE_USE", "HOVER", "CONFIRM ACCEPT \"again\""))
            assertThatThrownBy(() -> PlaywrightPageEngine.parseCommands("QUERY button\nCONFIRM ACCEPT \"text\"\n" + next)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Line 3");

        assertThatThrownBy(() -> PlaywrightPageEngine.parseCommands("CONFIRM ACCEPT \"text\"\n# no activation\n")).hasMessageContaining("Line 1", "following activation");
        assertThatThrownBy(() -> PlaywrightPageEngine.parseCommands("CONFIRM ACCEPT \"text\"\nUSE")).hasMessageContaining("previous QUERY");
    }

    @Test
    void scopesConfirmationToOneActivationAndClearsItWhenTheActionFails() throws Exception {
        var browser = mock(PlaywrightPageEngine.BrowserSession.class);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(2)).run();
            throw new ConfirmationFailure("CONFIRM_NOT_SHOWN");
        }).when(browser).withExpectedConfirmation(anyString(), anyLong(), any());
        var commands = PlaywrightPageEngine.parseCommands("QUERY button\nCONFIRM ACCEPT \"text\"\nUSE\nUSE");
        var state = new PlaywrightPageEngine.ScriptState();
        PlaywrightPageEngine.execute(new AlertExecutionContext(), browser, commands.get(0), 125, state);
        PlaywrightPageEngine.execute(new AlertExecutionContext(), browser, commands.get(1), 125, state);
        assertThatThrownBy(() -> PlaywrightPageEngine.execute(new AlertExecutionContext(), browser, commands.get(2), 125, state)).isInstanceOf(ConfirmationFailure.class);
        PlaywrightPageEngine.execute(new AlertExecutionContext(), browser, commands.get(3), 125, state);
        verify(browser).withExpectedConfirmation(org.mockito.ArgumentMatchers.eq("text"), org.mockito.ArgumentMatchers.eq(125L), any());
        verify(browser, times(2)).click(125);
    }

    @Test
    void savedAlertsRetainOnlyFixedFailureCodesAndCommandLocations() throws Exception {
        var browser = mock(PlaywrightPageEngine.BrowserSession.class);
        when(browser.navigate(anyString(), anyLong())).thenReturn(new PlaywrightPageEngine.Navigation(200, "text/html"));
        when(browser.url()).thenReturn("https://fixture.test/");
        doAnswer(invocation -> { throw new ConfirmationFailure("CONFIRM_MISMATCH"); }).when(browser).withExpectedConfirmation(anyString(), anyLong(), any());
        var evaluator = new PlaywrightPageEngine("https://fixture.test/", true, false, false, 1, 1, "CONFIRM ACCEPT \"private-dialog-text\"\nCLICK 10 10", engine -> browser);
        var result = evaluator.evaluate(new AlertExecutionContext());
        var report = (Map<?, ?>) ((List<?>) result.statusMessage().get("browserResults")).getFirst();
        assertThat(report.get("failureCode")).isEqualTo("CONFIRM_MISMATCH");
        assertThat(report.get("failedLine")).isEqualTo(2);
        assertThat(report.get("failedCommand")).isEqualTo("CLICK");
        assertThat(result.statusMessage().toString()).doesNotContain("private-dialog-text");
    }
}
