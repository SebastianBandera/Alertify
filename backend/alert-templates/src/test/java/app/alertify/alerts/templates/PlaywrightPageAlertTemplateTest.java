package app.alertify.alerts.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import app.alertify.alerts.AlertExecutionContext;
import app.alertify.alerts.AlertResult;
import app.alertify.alerts.execution.AlertExecutionStatus;
import app.alertify.alerts.template.annotation.AlertParameter;
import app.alertify.alerts.template.annotation.AlertTemplate;
import app.alertify.worker.contract.WorkerCapability;

class PlaywrightPageAlertTemplateTest {

    @Test
    void declaresPlaywrightCapabilityAndMultilineSteps() throws ReflectiveOperationException {
        AlertTemplate metadata = PlaywrightPageAlertTemplate.class.getAnnotation(AlertTemplate.class);

        assertEquals(WorkerCapability.PLAYWRIGHT, metadata.capability());
        assertEquals("app/alertify/alerts/templates/PlaywrightPageAlertTemplate.java", metadata.sourcePath());
        assertEquals("true", parameter("chromiumEnabled").defaultValue());
        assertEquals("false", parameter("firefoxEnabled").defaultValue());
        assertEquals("false", parameter("webkitEnabled").defaultValue());
        assertFalse(parameter("chromiumEnabled").bindingAllowed());
        assertEquals("10", parameter("loadTimeoutSeconds").defaultValue());
        assertEquals("5", parameter("elementTimeoutSeconds").defaultValue());
        assertTrue(parameter("steps").multiline());
        assertFalse(parameter("steps").required());
    }

    @Test
    void parsesEveryV1CommandAndKeepsConditionalTimeoutAfterTheSelector() {
        List<PlaywrightPageAlertTemplate.Command> commands = PlaywrightPageAlertTemplate.parseCommands("""
            # complete language sample
            QUERY form .country
            USE
            DOUBLE_USE
            HOVER
            FOCUS
            SELECT UY
            WAIT 25ms
            WAIT_VISIBLE .result panel
            WAIT_VISIBLE .slow-result 15s
            WAIT_HIDDEN .spinner 3s
            CHECK TEXT Ready now
            CHECK COUNT >= 2
            CHECK ENABLED
            CHECK DISABLED
            CHECK CHECKED
            CHECK UNCHECKED
            CHECK ATTRIBUTE data-state ready now
            CHECK URL **/finished
            RELOAD
            """);

        assertEquals(19, commands.size());
        assertEquals(PlaywrightPageAlertTemplate.CommandType.QUERY, commands.get(0).type());
        assertEquals("form .country", commands.get(0).first());
        assertEquals(25, commands.get(6).durationMillis());
        assertEquals(".result panel", commands.get(7).first());
        assertEquals(0, commands.get(7).durationMillis());
        assertEquals(".slow-result", commands.get(8).first());
        assertEquals(15_000, commands.get(8).durationMillis());
        assertEquals(".spinner", commands.get(9).first());
        assertEquals(3_000, commands.get(9).durationMillis());
        assertEquals(PlaywrightPageAlertTemplate.CountOperator.GREATER_OR_EQUAL, commands.get(11).countOperator());
        assertEquals("data-state", commands.get(16).first());
        assertEquals("ready now", commands.get(16).second());
        assertEquals(PlaywrightPageAlertTemplate.CommandType.RELOAD, commands.get(18).type());
    }

    @Test
    void rejectsInvalidLanguageBeforeOpeningTheBrowser() {
        assertThrows(IllegalArgumentException.class, () -> PlaywrightPageAlertTemplate.parseCommands("USE"));
        assertThrows(IllegalArgumentException.class, () -> PlaywrightPageAlertTemplate.parseCommands("QUERY .item\nRELOAD\nHOVER"));
        assertThrows(IllegalArgumentException.class, () -> PlaywrightPageAlertTemplate.parseCommands("WAIT 5"));
        assertThrows(IllegalArgumentException.class, () -> PlaywrightPageAlertTemplate.parseCommands("WAIT_VISIBLE .item 0s"));
        assertThrows(IllegalArgumentException.class, () -> PlaywrightPageAlertTemplate.parseCommands("QUERY .item\nCHECK COUNT approximately 2"));
        assertThrows(IllegalArgumentException.class, () -> PlaywrightPageAlertTemplate.parseCommands("QUERY .item\nCHECK ATTRIBUTE data-state"));
        assertThrows(IllegalArgumentException.class, () -> PlaywrightPageAlertTemplate.parseCommands("QUERY .item\nUNKNOWN"));
    }

    @Test
    void executesTheAnepNavigationSequenceAndDoesNotPersistCommandArguments() throws Exception {
        FakeBrowserSession session = new FakeBrowserSession();
        session.currentText = "Presentación y cometidos";
        session.finalUrl = "https://anep.edu.uy/contacto-anep?source=private";
        String commands = """
            QUERY button.nav-link.level-0:has-text("Institucional"):visible
            HOVER
            QUERY a.nav-link.level-2[href="/acerca-anep"]:visible
            CHECK TEXT Presentación y cometidos
            USE
            QUERY a.nav-link.level-0[href="/contacto-anep"]:has-text("Contacto"):visible
            USE
            """;
        AlertResult result = template("https://anep.edu.uy/?token=private", commands, session).evaluate(new AlertExecutionContext());
        Map<?, ?> browserResult = browserResult(result, 0);

        assertEquals(AlertExecutionStatus.SUCCESS, result.status());
        assertEquals(7, result.statusMessage().get("commandCount"));
        assertEquals(1, result.statusMessage().get("successfulBrowserCount"));
        assertEquals("chromium", browserResult.get("browser"));
        assertEquals(7, browserResult.get("completedCommandCount"));
        assertEquals("https://anep.edu.uy/contacto-anep", browserResult.get("finalUrl"));
        assertEquals(List.of("navigate", "query", "hover", "query", "check", "click", "query", "click", "close"), session.operations);
        assertFalse(result.statusMessage().toString().contains("Institucional"));
        assertFalse(result.statusMessage().toString().contains("Presentación"));
        assertFalse(result.statusMessage().toString().contains("private"));
    }

    @Test
    void executesSelectionPointerActionsWaitsAndChecks() throws Exception {
        FakeBrowserSession session = new FakeBrowserSession();
        session.currentText = "Ready";
        session.currentCount = 2;
        session.currentEnabled = true;
        session.currentChecked = true;
        session.attributes = Map.of("data-state", "ready");
        String commands = """
            QUERY select[name="country"]
            SELECT UY
            DOUBLE_USE
            HOVER
            FOCUS
            WAIT 1ms
            WAIT_VISIBLE .result 15s
            WAIT_HIDDEN .spinner
            CHECK TEXT Ready
            CHECK COUNT = 2
            CHECK ENABLED
            CHECK CHECKED
            CHECK ATTRIBUTE data-state ready
            CHECK URL **/done
            """;
        AlertResult result = template("https://example.test", commands, session).evaluate(new AlertExecutionContext());

        assertEquals(AlertExecutionStatus.SUCCESS, result.status());
        assertTrue(session.operations.contains("select:UY"));
        assertTrue(session.operations.contains("doubleClick"));
        assertTrue(session.operations.contains("waitVisible:.result:15000"));
        assertTrue(session.operations.contains("waitHidden:.spinner:5000"));
        assertTrue(session.operations.contains("checkUrl:**/done:5000"));
    }

    @Test
    void acceptsDisabledAndUncheckedChecks() throws Exception {
        FakeBrowserSession session = new FakeBrowserSession();
        session.currentEnabled = false;
        session.currentChecked = false;

        AlertResult result = template("https://example.test", "QUERY input\nCHECK DISABLED\nCHECK UNCHECKED", session).evaluate(new AlertExecutionContext());

        assertEquals(AlertExecutionStatus.SUCCESS, result.status());
    }

    @Test
    void reportsHttpErrorsWithoutRunningCommands() throws Exception {
        FakeBrowserSession session = new FakeBrowserSession();
        session.navigation = new PlaywrightPageAlertTemplate.Navigation(503);

        AlertResult result = template("https://example.test", "QUERY .never", session).evaluate(new AlertExecutionContext());
        Map<?, ?> browserResult = browserResult(result, 0);

        assertEquals(AlertExecutionStatus.WARN, result.status());
        assertEquals(1, result.statusMessage().get("warningBrowserCount"));
        assertEquals("WARN", browserResult.get("status"));
        assertEquals("httpStatus", browserResult.get("failureReason"));
        assertEquals(503, browserResult.get("loadStatusCode"));
        assertEquals(List.of("navigate", "close"), session.operations);
    }

    @Test
    void stopsAtTheFirstFailedCommandAndReportsOnlyItsLocation() throws Exception {
        FakeBrowserSession session = new FakeBrowserSession();
        session.failQuery = true;
        String secretSelector = "[data-secret=never-persist-this]";

        AlertResult result = template("https://example.test", "# comment\nQUERY " + secretSelector + "\nUSE", session).evaluate(new AlertExecutionContext());
        Map<?, ?> browserResult = browserResult(result, 0);

        assertEquals(AlertExecutionStatus.WARN, result.status());
        assertEquals("commandFailure", browserResult.get("failureReason"));
        assertEquals(2, browserResult.get("failedLine"));
        assertEquals("QUERY", browserResult.get("failedCommand"));
        assertEquals(0, browserResult.get("completedCommandCount"));
        assertFalse(result.statusMessage().toString().contains(secretSelector));
        assertFalse(session.operations.contains("click"));
    }

    @Test
    void reloadUsesTheLoadTimeoutAndClearsTheCurrentElement() throws Exception {
        FakeBrowserSession session = new FakeBrowserSession();
        AlertResult result = template("https://example.test", "QUERY body\nRELOAD\nQUERY main\nCHECK COUNT > 0", session).evaluate(new AlertExecutionContext());

        assertEquals(AlertExecutionStatus.SUCCESS, result.status());
        assertTrue(session.operations.contains("reload:10000"));
        assertEquals(200, browserResult(result, 0).get("reloadStatusCode"));
    }

    @Test
    void executesEnabledBrowsersSequentiallyAndContinuesAfterAWarning() throws Exception {
        Map<PlaywrightPageAlertTemplate.BrowserEngine, FakeBrowserSession> sessions = new EnumMap<>(PlaywrightPageAlertTemplate.BrowserEngine.class);
        for (PlaywrightPageAlertTemplate.BrowserEngine browser : PlaywrightPageAlertTemplate.BrowserEngine.values())
            sessions.put(browser, new FakeBrowserSession());

        sessions.get(PlaywrightPageAlertTemplate.BrowserEngine.CHROMIUM).navigation = new PlaywrightPageAlertTemplate.Navigation(503);
        List<PlaywrightPageAlertTemplate.BrowserEngine> opened = new ArrayList<>();
        PlaywrightPageAlertTemplate template = new PlaywrightPageAlertTemplate("https://example.test", true, true, true, 10, 5, "QUERY body", browser -> {
            opened.add(browser);
            return sessions.get(browser);
        });

        AlertResult result = template.evaluate(new AlertExecutionContext());

        assertEquals(AlertExecutionStatus.WARN, result.status());
        assertEquals(List.of(
            PlaywrightPageAlertTemplate.BrowserEngine.CHROMIUM,
            PlaywrightPageAlertTemplate.BrowserEngine.FIREFOX,
            PlaywrightPageAlertTemplate.BrowserEngine.WEBKIT
        ), opened);
        assertEquals(2, result.statusMessage().get("successfulBrowserCount"));
        assertEquals(1, result.statusMessage().get("warningBrowserCount"));
        assertEquals("chromium", browserResult(result, 0).get("browser"));
        assertEquals("WARN", browserResult(result, 0).get("status"));
        assertEquals("firefox", browserResult(result, 1).get("browser"));
        assertEquals("SUCCESS", browserResult(result, 1).get("status"));
        assertEquals("webkit", browserResult(result, 2).get("browser"));
        assertEquals("SUCCESS", browserResult(result, 2).get("status"));
    }

    @Test
    void skipsDisabledBrowsersWithoutChangingTheRelativeOrder() throws Exception {
        List<PlaywrightPageAlertTemplate.BrowserEngine> opened = new ArrayList<>();
        PlaywrightPageAlertTemplate template = new PlaywrightPageAlertTemplate("https://example.test", true, false, true, 10, 5, null, browser -> {
            opened.add(browser);
            return new FakeBrowserSession();
        });

        AlertResult result = template.evaluate(new AlertExecutionContext());

        assertEquals(AlertExecutionStatus.SUCCESS, result.status());
        assertEquals(List.of(
            PlaywrightPageAlertTemplate.BrowserEngine.CHROMIUM,
            PlaywrightPageAlertTemplate.BrowserEngine.WEBKIT
        ), opened);
    }

    @Test
    void rejectsAnExecutionWithoutBrowsersBeforeOpeningAPlaywrightSession() {
        AtomicBoolean opened = new AtomicBoolean();
        PlaywrightPageAlertTemplate template = new PlaywrightPageAlertTemplate("https://example.test", false, false, false, 10, 5, null, browser -> {
            opened.set(true);
            return new FakeBrowserSession();
        });

        assertThrows(IllegalArgumentException.class, () -> template.evaluate(new AlertExecutionContext()));
        assertFalse(opened.get());
    }

    @Test
    void rejectsInvalidUrlAndTimeoutsBeforeOpeningTheBrowser() {
        FakeBrowserSession session = new FakeBrowserSession();

        assertThrows(IllegalArgumentException.class, () -> template("file:///tmp/test", null, session).evaluate(new AlertExecutionContext()));
        assertThrows(IllegalArgumentException.class, () -> new PlaywrightPageAlertTemplate("https://example.test", true, false, false, 0, 5, null, browser -> session).evaluate(new AlertExecutionContext()));
        assertThrows(IllegalArgumentException.class, () -> new PlaywrightPageAlertTemplate("https://example.test", true, false, false, 10, -1, null, browser -> session).evaluate(new AlertExecutionContext()));
        assertTrue(session.operations.isEmpty());
    }

    private static PlaywrightPageAlertTemplate template(String url, String commands, FakeBrowserSession session) {
        return new PlaywrightPageAlertTemplate(url, true, false, false, 10, 5, commands, browser -> session);
    }

    private static Map<?, ?> browserResult(AlertResult result, int index) {
        return (Map<?, ?>) ((List<?>) result.statusMessage().get("browserResults")).get(index);
    }

    private static AlertParameter parameter(String name) throws ReflectiveOperationException {
        return PlaywrightPageAlertTemplate.class.getDeclaredField(name).getAnnotation(AlertParameter.class);
    }

    private static final class FakeBrowserSession implements PlaywrightPageAlertTemplate.BrowserSession {

        private final List<String> operations = new ArrayList<>();
        private PlaywrightPageAlertTemplate.Navigation navigation = new PlaywrightPageAlertTemplate.Navigation(200);
        private PlaywrightPageAlertTemplate.Navigation reload = new PlaywrightPageAlertTemplate.Navigation(200);
        private String finalUrl = "https://example.test/done";
        private String currentText = "";
        private int currentCount = 1;
        private boolean currentEnabled = true;
        private boolean currentChecked;
        private Map<String, String> attributes = Map.of();
        private boolean failQuery;

        @Override
        public PlaywrightPageAlertTemplate.Navigation navigate(String url, long timeoutMillis) {
            operations.add("navigate");
            return navigation;
        }

        @Override
        public PlaywrightPageAlertTemplate.Navigation reload(long timeoutMillis) {
            operations.add("reload:" + timeoutMillis);
            return reload;
        }

        @Override
        public String url() {
            return finalUrl;
        }

        @Override
        public void query(String selector, long timeoutMillis) {
            operations.add("query");
            if (failQuery)
                throw new IllegalStateException("missing");
        }

        @Override
        public void click(long timeoutMillis) {
            operations.add("click");
        }

        @Override
        public void doubleClick(long timeoutMillis) {
            operations.add("doubleClick");
        }

        @Override
        public void hover(long timeoutMillis) {
            operations.add("hover");
        }

        @Override
        public void focus(long timeoutMillis) {
            operations.add("focus");
        }

        @Override
        public void select(String value, long timeoutMillis) {
            operations.add("select:" + value);
        }

        @Override
        public void waitVisible(String selector, long timeoutMillis) {
            operations.add("waitVisible:" + selector + ":" + timeoutMillis);
        }

        @Override
        public void waitHidden(String selector, long timeoutMillis) {
            operations.add("waitHidden:" + selector + ":" + timeoutMillis);
        }

        @Override
        public void check(BooleanSupplier condition, long timeoutMillis) {
            operations.add("check");
            if (!condition.getAsBoolean())
                throw new IllegalStateException("condition failed");
        }

        @Override
        public void checkUrl(String glob, long timeoutMillis) {
            operations.add("checkUrl:" + glob + ":" + timeoutMillis);
        }

        @Override
        public String currentText() {
            return currentText;
        }

        @Override
        public int currentCount() {
            return currentCount;
        }

        @Override
        public boolean currentEnabled() {
            return currentEnabled;
        }

        @Override
        public boolean currentChecked() {
            return currentChecked;
        }

        @Override
        public String currentAttribute(String name) {
            return attributes.get(name);
        }

        @Override
        public void close() {
            operations.add("close");
        }
    }
}
