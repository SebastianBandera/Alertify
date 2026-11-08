package app.alertify.playwright;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.microsoft.playwright.TimeoutError;

import app.alertify.alerts.AlertExecutionContext;
import app.alertify.playwright.PlaywrightPageEngine.BrowserEngine;
import app.alertify.playwright.PlaywrightPageEngine.PlaywrightBrowserSession;

@EnabledIfEnvironmentVariable(named = "ALERTIFY_BROWSER_TESTS", matches = "true")
class FlutterAccessibilityBrowserTest {
    private static final String FIXTURE = """
            <flutter-view></flutter-view><button id=selected>Selected</button><div id=count>0</div>
            <script>
            function install() {
              const control = document.createElement('flt-semantics-placeholder');
              control.setAttribute('role', 'button');
              control.setAttribute('aria-label', 'Activar accesibilidad');
              control.style.cssText = 'position:absolute;left:-1px;top:-1px;width:1px;height:1px';
              control.onclick = () => {
                document.querySelector('#count').textContent = String(Number(document.querySelector('#count').textContent) + 1);
                setTimeout(() => {
                  const host = document.createElement('flt-semantics-host');
                  host.innerHTML = '<flt-semantics role="button" aria-label="Ready"></flt-semantics>';
                  document.querySelector('flutter-view').append(host);
                  control.remove();
                }, 100);
              };
              document.body.append(control);
            }
            setTimeout(install, 100);
            </script>
            """;

    @ParameterizedTest
    @ValueSource(strings = { "chromium", "firefox", "webkit" })
    void activatesOffscreenControlOnceAndPreservesSelection(String engine) throws Exception {
        try (var session = PlaywrightBrowserSession.open(BrowserEngine.valueOf(engine.toUpperCase(java.util.Locale.ROOT)))) {
            session.page().setContent(FIXTURE);
            session.query("#selected", 1000);
            var context = new AlertExecutionContext();
            var command = PlaywrightPageEngine.parseCommands("ENABLE_ACCESSIBILITY").getFirst();
            PlaywrightPageEngine.execute(context, session, command, 3000);
            PlaywrightPageEngine.execute(context, session, command, 3000);
            assertThat(session.currentText()).isEqualTo("Selected");
            assertThat(session.page().locator("#count").innerText()).isEqualTo("1");
            assertThat(session.page().locator("flt-semantics-host flt-semantics").count()).isEqualTo(1);

            session.page().setContent("<flt-semantics-placeholder></flt-semantics-placeholder><flt-semantics-placeholder></flt-semantics-placeholder>");
            assertThatThrownBy(() -> session.enableAccessibility(300)).isInstanceOf(IllegalStateException.class).hasMessageContaining("unambiguous");
            session.page().setContent("<flutter-view></flutter-view><flutter-view></flutter-view>");
            assertThatThrownBy(() -> session.enableAccessibility(300)).isInstanceOf(IllegalStateException.class);
            session.page().setContent("<flutter-view></flutter-view><flt-semantics-placeholder></flt-semantics-placeholder>");
            assertThatThrownBy(() -> session.enableAccessibility(300)).isInstanceOf(TimeoutError.class);
            session.page().setContent("<p>Not Flutter</p>");
            assertThatThrownBy(() -> session.enableAccessibility(300)).isInstanceOf(TimeoutError.class);
            session.page().setContent("<div id=count>0</div><flt-semantics-placeholder onclick=\"document.querySelector('#count').textContent='1'\"></flt-semantics-placeholder><flt-semantics-host><flt-semantics></flt-semantics></flt-semantics-host>");
            assertThatThrownBy(() -> session.enableAccessibility(300)).isInstanceOf(TimeoutError.class);
            assertThat(session.page().locator("#count").innerText()).isEqualTo("0");
            session.page().setContent("<flutter-view></flutter-view><flt-semantics-host><flt-semantics></flt-semantics></flt-semantics-host>");
            assertThatThrownBy(() -> session.enableAccessibility(300)).isInstanceOf(TimeoutError.class);
        }
    }
}
