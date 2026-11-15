package app.alertify.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.TimeoutError;

import app.alertify.alerts.AlertExecutionContext;
import app.alertify.playwright.PlaywrightPageEngine.BrowserEngine;
import app.alertify.playwright.PlaywrightPageEngine.PlaywrightBrowserSession;

@EnabledIfEnvironmentVariable(named = "ALERTIFY_BROWSER_TESTS", matches = "true")
class ConfirmationBrowserTest {
    private static final String EXPECTED = " ¿Cerrar la sesión? ";
    private static final String HTML = """
            <style>body{margin:0}button{width:100px;height:40px}</style>
            <button id=logout>Cerrar sesión</button><p id=result>Pendiente</p>
            <script>
            document.querySelector('button').onclick = () => {
              if (confirm(' ¿Cerrar la sesión? ')) {
                document.querySelector('#result').textContent='Sesión cerrada';
                location.hash='login';
              }
            };
            </script>
            """;

    @ParameterizedTest
    @ValueSource(strings = { "chromium", "firefox", "webkit" })
    void acceptsOneExactConfirmationForEverySupportedActivationAndChecksDestination(String engine) throws Exception {
        try (var session = PlaywrightBrowserSession.open(BrowserEngine.valueOf(engine.toUpperCase(Locale.ROOT)))) {
            for (String action : List.of("USE", "DOM_CLICK", "PRESS Enter", "CLICK 40 20")) {
                session.page().setContent(HTML);
                execute(session, "QUERY #logout\nCONFIRM ACCEPT \"" + EXPECTED + "\"\n# Cerrar sesión\n\n" + action + "\nCHECK URL **#login\nQUERY #result\nCHECK TEXT Sesión cerrada", 1000);
                assertThat(session.lastDialog()).isEqualTo(new PlaywrightBrowserSession.DialogObservation("confirm", EXPECTED, true, false));
                execute(session, "QUERY #logout\nUSE", 1000);
                assertThat(session.lastDialog().accepted()).isFalse();
                assertThat(session.lastDialog().dismissed()).isTrue();
            }
            session.page().setContent("<button onclick=\"setTimeout(() => confirm('Delayed'), 50)\">Run</button>");
            execute(session, "QUERY button\nCONFIRM ACCEPT \"Delayed\"\nUSE", 1000);
            assertThat(session.lastDialog().accepted()).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "chromium", "firefox", "webkit" })
    void dismissesDialogsProcessedAfterTheDeadlineEvenBeforeTheActionReturns(String engine) throws Exception {
        try (var session = PlaywrightBrowserSession.open(BrowserEngine.valueOf(engine.toUpperCase(Locale.ROOT)))) {
            session.page().setContent("<button onclick=\"setTimeout(() => confirm('Expected'), 200)\">Run</button>");
            session.query("button", 1000);
            assertThatThrownBy(() -> execute(session, "QUERY button\nCONFIRM ACCEPT \"Expected\"\nDOM_CLICK", 150))
                    .isInstanceOfSatisfying(ConfirmationFailure.class, failure -> assertThat(failure.code).isEqualTo("CONFIRM_NOT_SHOWN"));
            session.page().waitForTimeout(250);
            assertThat(session.lastDialog().accepted()).isFalse();
            assertThat(session.lastDialog().dismissed()).isTrue();

            assertThatThrownBy(() -> session.withExpectedConfirmation("Expected", 25, () -> {
                session.page().evaluate("setTimeout(() => confirm('Expected'), 25)");
                // Do not pump Playwright events until after the deadline; the action is still running.
                java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(100));
                session.page().waitForTimeout(30);
            })).isInstanceOfSatisfying(ConfirmationFailure.class, failure -> assertThat(failure.code).isEqualTo("CONFIRM_NOT_SHOWN"));
            assertThat(session.lastDialog().accepted()).isFalse();
            assertThat(session.lastDialog().dismissed()).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "chromium", "firefox", "webkit" })
    void dismissesMismatchesUnexpectedTypesAndSecondDialogsAndClearsFailedExpectations(String engine) throws Exception {
        try (var session = PlaywrightBrowserSession.open(BrowserEngine.valueOf(engine.toUpperCase(Locale.ROOT)))) {
            for (String expected : List.of("¿Cerrar la sesión?", " ¿cerrar la sesión? ", " ¿Cerrar la sesion? ", "*")) {
                session.page().setContent(HTML);
                assertFailure(session, "QUERY #logout\nCONFIRM ACCEPT \"" + expected + "\"\nUSE", "CONFIRM_MISMATCH");
                assertThat(session.page().locator("#result").innerText()).isEqualTo("Pendiente");
                assertThat(session.lastDialog().dismissed()).isTrue();
            }
            for (String dialog : List.of("alert('Expected')", "prompt('Expected')", "confirm('Expected');confirm('Expected')", "confirm('Expected');alert('Unexpected')")) {
                session.page().setContent("<button onclick=\"" + dialog + "\">Run</button>");
                assertFailure(session, "QUERY button\nCONFIRM ACCEPT \"Expected\"\nUSE", "CONFIRM_MISMATCH");
                assertThat(session.lastDialog().dismissed()).isTrue();
            }
            session.page().setContent("<button>Run</button>");
            assertFailure(session, "QUERY button\nCONFIRM ACCEPT \"Expected\"\nUSE", "CONFIRM_NOT_SHOWN");
            session.page().setContent("<button disabled onclick=\"confirm('Expected')\">Run</button>");
            assertThatThrownBy(() -> execute(session, "QUERY button\nCONFIRM ACCEPT \"Expected\"\nUSE", 150)).isInstanceOfSatisfying(TimeoutError.class, failure -> {
                var command = PlaywrightPageEngine.parseCommands("QUERY button\nCONFIRM ACCEPT \"Expected\"\nUSE").getLast();
                assertThat(new BrowserStepFailure(command, failure).code).isEqualTo("ELEMENT_TIMEOUT");
            });
            session.page().setContent("<button onclick=\"confirm('Expected')\">Run</button>");
            execute(session, "QUERY button\nUSE", 1000);
            assertThat(session.lastDialog().accepted()).isFalse();
        }
    }

    private void assertFailure(PlaywrightBrowserSession session, String script, String code) {
        session.page().locator("button").click(new Locator.ClickOptions().setTrial(true).setTimeout(1000));
        assertThatThrownBy(() -> execute(session, script, 1000)).isInstanceOfSatisfying(ConfirmationFailure.class, failure -> assertThat(failure.code).isEqualTo(code));
    }

    private void execute(PlaywrightBrowserSession session, String script, long timeout) throws Exception {
        var state = new PlaywrightPageEngine.ScriptState();
        for (var command : PlaywrightPageEngine.parseCommands(script))
            PlaywrightPageEngine.execute(new AlertExecutionContext(), session, command, timeout, state);
    }
}
