package app.alertify.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.Test;

import app.alertify.alerts.AlertExecutionContext;

class PlaywrightCoordinateClickTest {
    @Test
    void parsesPixelsPercentagesMixedUnitsAndAnExplicitTenSecondWaitWithoutAQuery() {
        var commands = PlaywrightPageEngine.parseCommands("WAIT 10s\nCLICK 720 450\nCLICK 50% 50%\nCLICK 12.5% 450.25\nCLICK 0 100%");
        assertThat(commands.getFirst().durationMillis()).isEqualTo(10_000);
        assertThat(commands.subList(1, commands.size())).allSatisfy(command -> {
            assertThat(command.type()).isEqualTo(PlaywrightPageEngine.CommandType.CLICK);
            assertThat(command.keyword()).isEqualTo("CLICK");
        });
        assertThat(commands.get(2).first()).isEqualTo("50%");
        assertThat(commands.get(3).second()).isEqualTo("450.25");
        assertThat(PlaywrightPageEngine.requiredValues("CLICK 50% 50%")).isEmpty();
    }

    @Test
    void rejectsMalformedNegativeNonFiniteAndOutOfRangePercentageOperands() {
        for (String invalid : List.of("", "10", "10 20 30", "10,20", "-1 20", "20 -1", "NaN 20", "Infinity 20", "1e2 20", "50%% 50%", "101% 0", "0 100.01%", "secrets.X 20", "9".repeat(400) + " 0"))
            assertThatThrownBy(() -> PlaywrightPageEngine.parseCommands("CLICK " + invalid))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Line 1", "CLICK requires");
    }

    @Test
    void executesCoordinateClicksWithoutSelectingOrChangingTheFillTarget() throws Exception {
        var browser = mock(PlaywrightPageEngine.BrowserSession.class);
        var context = new AlertExecutionContext();
        for (var command : PlaywrightPageEngine.parseCommands("CLICK 50% 50%\nQUERY #field\nCLICK 720 450\nUSE"))
            PlaywrightPageEngine.execute(context, browser, command, 500);

        var ordered = inOrder(browser);
        ordered.verify(browser).clickAt(50, 50, true, true);
        ordered.verify(browser).query("#field", 500);
        ordered.verify(browser).clickAt(720, 450, false, false);
        ordered.verify(browser).click(500);
        ordered.verifyNoMoreInteractions();
        assertThatThrownBy(() -> PlaywrightPageEngine.parseCommands("CLICK 50% 50%\nFILL secrets.X"))
                .hasMessageContaining("previous QUERY");
    }

}
