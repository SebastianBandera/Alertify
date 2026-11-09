package app.alertify.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.Test;

import app.alertify.alerts.AlertExecutionContext;

class PlaywrightElementActivationTest {
    @Test
    void requiresQueryAndAcceptsOnlyEnterAndArgumentFreeDomClick() {
        for (String action : List.of("PRESS Enter", "DOM_CLICK")) {
            assertThatThrownBy(() -> PlaywrightPageEngine.parseCommands(action)).hasMessageContaining("previous QUERY");
            assertThatThrownBy(() -> PlaywrightPageEngine.parseCommands("QUERY button\nRELOAD\n" + action)).hasMessageContaining("previous QUERY");
        }
        for (String invalid : List.of("PRESS", "PRESS enter", "PRESS Escape", "PRESS Enter extra", "PRESS secrets.X", "DOM_CLICK button", "DOM_CLICK () => alert(1)"))
            assertThatThrownBy(() -> PlaywrightPageEngine.parseCommands("QUERY button\n" + invalid)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Line 2");

        assertThat(PlaywrightPageEngine.requiredValues("QUERY button\nPRESS Enter\nDOM_CLICK")).isEmpty();
    }

    @Test
    void dispatchesEachActivationOnceWithTheElementTimeoutAndPreservesSelection() throws Exception {
        var browser = mock(PlaywrightPageEngine.BrowserSession.class);
        for (var command : PlaywrightPageEngine.parseCommands("QUERY button\nPRESS Enter\nDOM_CLICK\nUSE"))
            PlaywrightPageEngine.execute(new AlertExecutionContext(), browser, command, 750);

        var ordered = inOrder(browser);
        ordered.verify(browser).query("button", 750);
        ordered.verify(browser).pressEnter(750);
        ordered.verify(browser).domClick(750);
        ordered.verify(browser).click(750);
        ordered.verifyNoMoreInteractions();
    }
}
