package app.alertify.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.Test;

import app.alertify.alerts.AlertExecutionContext;

class PlaywrightPointerNavigationTest {
    @Test
    void parsesMovesAndSignedScrollDeltasWithoutSelectingAnElement() {
        var commands = PlaywrightPageEngine.parseCommands("MOVE 720 450\nMOVE 12.5% 450.25\nMOVE 100% 100%\nSCROLL 0 100000\nWAIT 10s\nSCROLL -12.5 +300\nSCROLL 0 -100000");
        assertThat(commands).hasSize(7);
        assertThat(commands.get(1).first()).isEqualTo("12.5%");
        assertThat(commands.get(2).second()).isEqualTo("100%");
        assertThat(commands.get(3).type()).isEqualTo(PlaywrightPageEngine.CommandType.SCROLL);
        assertThat(commands.get(4).durationMillis()).isEqualTo(10000);
        assertThat(PlaywrightPageEngine.requiredValues("MOVE 50% 50%\nSCROLL 0 1000")).isEmpty();
        assertThatThrownBy(() -> PlaywrightPageEngine.parseCommands("MOVE 50% 50%\nSCROLL 0 10\nFILL secrets.X")).hasMessageContaining("previous QUERY");
    }

    @Test
    void rejectsInvalidCoordinatesAndScrollOperands() {
        for (String invalid : List.of("", "1", "1 2 3", "-1 20", "101% 0", "0 100.01%", "NaN 0", "1e2 0", "9".repeat(400) + " 0"))
            assertThatThrownBy(() -> PlaywrightPageEngine.parseCommands("MOVE " + invalid)).hasMessageContaining("Line 1", "MOVE requires");

        for (String invalid : List.of("", "1", "1 2 3", "10% 20", "NaN 0", "Infinity 0", "1e2 0", "secrets.X 0", "9".repeat(400) + " 0"))
            assertThatThrownBy(() -> PlaywrightPageEngine.parseCommands("SCROLL " + invalid)).hasMessageContaining("Line 1", "SCROLL requires");
    }

    @Test
    void dispatchesOnlyMovementAndWheelEventsAndPreservesTheQueryTarget() throws Exception {
        var browser = mock(PlaywrightPageEngine.BrowserSession.class);
        for (var command : PlaywrightPageEngine.parseCommands("QUERY #field\nMOVE 50% 450\nSCROLL -100 100000\nSCROLL 0 -120.5\nUSE"))
            PlaywrightPageEngine.execute(new AlertExecutionContext(), browser, command, 500);

        var ordered = inOrder(browser);
        ordered.verify(browser).query("#field", 500);
        ordered.verify(browser).moveTo(50, 450, true, false);
        ordered.verify(browser).scrollBy(-100, 100000);
        ordered.verify(browser).scrollBy(0, -120.5);
        ordered.verify(browser).click(500);
        ordered.verifyNoMoreInteractions();
    }
}
