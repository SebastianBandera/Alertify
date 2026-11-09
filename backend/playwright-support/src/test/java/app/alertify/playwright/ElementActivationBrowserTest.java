package app.alertify.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import app.alertify.alerts.AlertExecutionContext;
import app.alertify.playwright.PlaywrightPageEngine.BrowserEngine;
import app.alertify.playwright.PlaywrightPageEngine.PlaywrightBrowserSession;

@EnabledIfEnvironmentVariable(named = "ALERTIFY_BROWSER_TESTS", matches = "true")
class ElementActivationBrowserTest {
    @ParameterizedTest
    @ValueSource(strings = { "chromium", "firefox", "webkit" })
    void activatesExactlyOnceThroughDomAndKeyboardAndRetainsStrictVisibilityAndEnabledChecks(String engine) throws Exception {
        try (var session = PlaywrightBrowserSession.open(BrowserEngine.valueOf(engine.toUpperCase(Locale.ROOT)))) {
            for (String action : List.of("DOM_CLICK", "PRESS Enter")) {
                session.page().setContent("""
                        <flutter-view><flt-semantics-host>
                        <flt-semantics id=target role=button tabindex=0 style="display:block;width:100px;height:40px">Access</flt-semantics>
                        </flt-semantics-host></flutter-view><div id=count>0</div>
                        <script>
                        {
                        const target = document.querySelector('#target');
                        target.onclick = () => { document.querySelector('#count').textContent = String(Number(document.querySelector('#count').textContent) + 1); };
                        target.onkeydown = e => { if (e.key === 'Enter') target.click(); };
                        }
                        </script>
                        <div style="position:fixed;inset:0;background:transparent"></div>
                        """);
                execute(session, "QUERY #target\n" + action + "\nCHECK TEXT Access", 1000);
                assertThat(session.page().locator("#count").innerText()).isEqualTo("1");
                assertThat(session.currentCount()).isEqualTo(1);

                session.page().setContent("<button disabled onclick=\"this.textContent='clicked'\">Disabled</button>");
                assertThatThrownBy(() -> execute(session, "QUERY button\n" + action, 150)).isInstanceOf(RuntimeException.class);
                assertThat(session.page().locator("button").innerText()).isEqualTo("Disabled");
                session.page().setContent("<div role=button aria-disabled=true tabindex=0>Disabled</div>");
                assertThatThrownBy(() -> execute(session, "QUERY div\n" + action, 150)).isInstanceOf(RuntimeException.class);

                session.page().setContent("<button>A</button><button>B</button>");
                assertThatThrownBy(() -> execute(session, "QUERY button\n" + action, 150)).hasMessageContaining("strict mode violation");
                session.page().setContent("<button hidden>Hidden</button><button onclick=\"this.textContent='Activated'\">Visible</button>");
                execute(session, "QUERY button\n" + action + "\nCHECK TEXT Activated", 1000);
                assertThat(session.page().locator("button[hidden]").innerText()).isEqualTo("Hidden");

                session.page().setContent("<button disabled onclick=\"this.textContent='Ready'\">Waiting</button><script>setTimeout(() => document.querySelector('button').disabled = false, 200)</script>");
                execute(session, "QUERY button\n" + action + "\nCHECK TEXT Ready", 2000);
                session.page().setContent("<button onclick=\"location.hash='destination'\">Navigate</button>");
                execute(session, "QUERY button\n" + action + "\nCHECK URL **#destination", 2000);
            }
        }
    }

    private void execute(PlaywrightBrowserSession session, String script, long timeout) throws Exception {
        for (var command : PlaywrightPageEngine.parseCommands(script))
            PlaywrightPageEngine.execute(new AlertExecutionContext(), session, command, timeout);
    }
}
