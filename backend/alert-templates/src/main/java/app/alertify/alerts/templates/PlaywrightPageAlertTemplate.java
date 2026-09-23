package app.alertify.alerts.templates;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;

import app.alertify.alerts.AlertEvaluator;
import app.alertify.alerts.AlertExecutionContext;
import app.alertify.alerts.AlertResult;
import app.alertify.alerts.template.annotation.AlertParameter;
import app.alertify.alerts.template.annotation.AlertTemplate;
import app.alertify.alerts.template.annotation.AlertTemplateTag;
import app.alertify.worker.contract.WorkerCapability;

/**
 * Loads a page in Chromium and runs a small, fail-fast browser monitoring DSL.
 * Command arguments are deliberately excluded from the persisted status message
 * because the multiline script may be backed by a configuration or secret.
 */
@AlertTemplate(
    nameKey = "alerts.template.playwrightPage.name",
    descriptionKey = "alerts.template.playwrightPage.description",
    tags = @AlertTemplateTag(nameKey = "alerts.templateTag.network", color = "#0EA5E9"),
    capability = WorkerCapability.PLAYWRIGHT,
    sourcePath = "app/alertify/alerts/templates/PlaywrightPageAlertTemplate.java"
)
public final class PlaywrightPageAlertTemplate implements AlertEvaluator {

    private static final Pattern DURATION = Pattern.compile("^(\\d+)(ms|s)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SELECTOR_TIMEOUT = Pattern.compile("^(.*\\S)\\s+(\\d+(?:ms|s))$", Pattern.CASE_INSENSITIVE);
    private static final Pattern COUNT_CHECK = Pattern.compile("^(=|!=|>=|<=|>|<)\\s+(\\d+)$");
    private static final long POLL_INTERVAL_MILLIS = 50;

    @AlertParameter(
        labelKey = "alerts.template.playwrightPage.url",
        descriptionKey = "alerts.template.playwrightPage.urlDescription",
        order = 1
    )
    private final String url;

    @AlertParameter(
        labelKey = "alerts.template.playwrightPage.loadTimeoutSeconds",
        descriptionKey = "alerts.template.playwrightPage.loadTimeoutSecondsDescription",
        options = { "5", "10", "15", "30", "60" },
        defaultValue = "10",
        order = 2
    )
    private final int loadTimeoutSeconds;

    @AlertParameter(
        labelKey = "alerts.template.playwrightPage.elementTimeoutSeconds",
        descriptionKey = "alerts.template.playwrightPage.elementTimeoutSecondsDescription",
        options = { "1", "3", "5", "10", "30" },
        defaultValue = "5",
        order = 3
    )
    private final int elementTimeoutSeconds;

    @AlertParameter(
        labelKey = "alerts.template.playwrightPage.steps",
        descriptionKey = "alerts.template.playwrightPage.stepsDescription",
        multiline = true,
        required = false,
        order = 4
    )
    private final String steps;

    private final BrowserSessionFactory sessionFactory;

    public PlaywrightPageAlertTemplate(String url, int loadTimeoutSeconds, int elementTimeoutSeconds, String steps) {
        this(url, loadTimeoutSeconds, elementTimeoutSeconds, steps, PlaywrightBrowserSession::open);
    }

    PlaywrightPageAlertTemplate(String url, int loadTimeoutSeconds, int elementTimeoutSeconds, String steps, BrowserSessionFactory sessionFactory) {
        this.url = url;
        this.loadTimeoutSeconds = loadTimeoutSeconds;
        this.elementTimeoutSeconds = elementTimeoutSeconds;
        this.steps = steps;
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory must not be null");
    }

    @Override
    public AlertResult evaluate(AlertExecutionContext context) throws Exception {
        URI configuredUrl = parseUrl(url);
        long loadTimeoutMillis = timeoutMillis(loadTimeoutSeconds, "loadTimeoutSeconds");
        long elementTimeoutMillis = timeoutMillis(elementTimeoutSeconds, "elementTimeoutSeconds");
        List<Command> commands = parseCommands(steps);
        String endpoint = safeUrl(configuredUrl.toString());
        Map<String, Object> statusMessage = baseStatus(endpoint, loadTimeoutSeconds, elementTimeoutSeconds, commands.size());

        try (BrowserSession session = sessionFactory.open()) {
            long startedNanos = System.nanoTime();
            Navigation navigation;
            try {
                navigation = session.navigate(configuredUrl.toString(), loadTimeoutMillis);
            } catch (RuntimeException exception) {
                statusMessage.put("loadDurationMs", elapsedMillis(startedNanos));
                statusMessage.put("finalUrl", safeUrl(session.url()));
                return warn(context, statusMessage, isPlaywrightTimeout(exception) ? "loadTimeout" : "navigationFailure", null, null, endpoint);
            }

            long loadDurationMillis = elapsedMillis(startedNanos);
            updateNavigationStatus(statusMessage, session, navigation, loadDurationMillis, "load");
            AlertResult navigationWarning = navigationWarning(context, statusMessage, navigation, loadDurationMillis, loadTimeoutMillis, null, endpoint);
            if (navigationWarning != null)
                return navigationWarning;

            int completed = 0;
            for (Command command : commands) {
                if (command.type() == CommandType.RELOAD) {
                    startedNanos = System.nanoTime();
                    try {
                        navigation = session.reload(loadTimeoutMillis);
                    } catch (RuntimeException exception) {
                        statusMessage.put("reloadDurationMs", elapsedMillis(startedNanos));
                        statusMessage.put("finalUrl", safeUrl(session.url()));
                        return warn(context, statusMessage, isPlaywrightTimeout(exception) ? "reloadTimeout" : "reloadFailure", command, "RELOAD", endpoint);
                    }
                    loadDurationMillis = elapsedMillis(startedNanos);
                    updateNavigationStatus(statusMessage, session, navigation, loadDurationMillis, "reload");
                    navigationWarning = navigationWarning(context, statusMessage, navigation, loadDurationMillis, loadTimeoutMillis, command, endpoint);
                    if (navigationWarning != null)
                        return navigationWarning;
                } else {
                    try {
                        execute(session, command, elementTimeoutMillis);
                    } catch (RuntimeException exception) {
                        return warn(context, statusMessage, "commandFailure", command, command.keyword(), endpoint);
                    }
                }
                completed++;
                statusMessage.put("completedCommandCount", completed);
            }

            statusMessage.put("completedCommandCount", completed);
            statusMessage.put("finalUrl", safeUrl(session.url()));
            context.setState("endpoint=" + endpoint + ";status=SUCCESS;commands=" + completed);
            return AlertResult.success(statusMessage);
        }
    }

    static List<Command> parseCommands(String configured) {
        if (configured == null || configured.isBlank())
            return List.of();

        List<Command> commands = new ArrayList<>();
        boolean currentElement = false;
        String[] lines = configured.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index].trim();
            if (line.isEmpty() || line.startsWith("#"))
                continue;

            int lineNumber = index + 1;
            String[] tokens = line.split("\\s+", 2);
            String keyword = tokens[0].toUpperCase(Locale.ROOT);
            String arguments = tokens.length == 2 ? tokens[1].trim() : "";
            Command command;
            switch (keyword) {
                case "QUERY" -> {
                    command = command(lineNumber, CommandType.QUERY, requireArgument(arguments, lineNumber, "QUERY"));
                    currentElement = true;
                }
                case "USE" -> command = currentCommand(lineNumber, CommandType.USE, arguments, currentElement);
                case "DOUBLE_USE" -> command = currentCommand(lineNumber, CommandType.DOUBLE_USE, arguments, currentElement);
                case "HOVER" -> command = currentCommand(lineNumber, CommandType.HOVER, arguments, currentElement);
                case "FOCUS" -> command = currentCommand(lineNumber, CommandType.FOCUS, arguments, currentElement);
                case "SELECT" -> {
                    requireCurrent(currentElement, lineNumber, "SELECT");
                    command = command(lineNumber, CommandType.SELECT, requireArgument(arguments, lineNumber, "SELECT"));
                }
                case "WAIT" -> command = durationCommand(lineNumber, arguments);
                case "WAIT_VISIBLE" -> command = selectorWaitCommand(lineNumber, CommandType.WAIT_VISIBLE, arguments);
                case "WAIT_HIDDEN" -> command = selectorWaitCommand(lineNumber, CommandType.WAIT_HIDDEN, arguments);
                case "RELOAD" -> {
                    requireNoArguments(arguments, lineNumber, "RELOAD");
                    command = command(lineNumber, CommandType.RELOAD);
                    currentElement = false;
                }
                case "CHECK" -> command = checkCommand(lineNumber, arguments, currentElement);
                default -> throw lineError(lineNumber, "Unsupported command '" + tokens[0] + "'");
            }
            commands.add(command);
        }
        return List.copyOf(commands);
    }

    private static Command checkCommand(int lineNumber, String arguments, boolean currentElement) {
        String value = requireArgument(arguments, lineNumber, "CHECK");
        String[] tokens = value.split("\\s+", 2);
        String check = tokens[0].toUpperCase(Locale.ROOT);
        String checkArguments = tokens.length == 2 ? tokens[1].trim() : "";
        return switch (check) {
            case "TEXT" -> {
                requireCurrent(currentElement, lineNumber, "CHECK TEXT");
                yield command(lineNumber, CommandType.CHECK_TEXT, requireArgument(checkArguments, lineNumber, "CHECK TEXT"));
            }
            case "COUNT" -> {
                requireCurrent(currentElement, lineNumber, "CHECK COUNT");
                Matcher matcher = COUNT_CHECK.matcher(checkArguments);
                if (!matcher.matches())
                    throw lineError(lineNumber, "CHECK COUNT must be '<operator> <non-negative integer>'");

                yield new Command(lineNumber, CommandType.CHECK_COUNT, null, null, 0, CountOperator.parse(matcher.group(1)), Integer.parseInt(matcher.group(2)));
            }
            case "ENABLED" -> currentCheck(lineNumber, CommandType.CHECK_ENABLED, checkArguments, currentElement, "CHECK ENABLED");
            case "DISABLED" -> currentCheck(lineNumber, CommandType.CHECK_DISABLED, checkArguments, currentElement, "CHECK DISABLED");
            case "CHECKED" -> currentCheck(lineNumber, CommandType.CHECK_CHECKED, checkArguments, currentElement, "CHECK CHECKED");
            case "UNCHECKED" -> currentCheck(lineNumber, CommandType.CHECK_UNCHECKED, checkArguments, currentElement, "CHECK UNCHECKED");
            case "ATTRIBUTE" -> {
                requireCurrent(currentElement, lineNumber, "CHECK ATTRIBUTE");
                String[] attribute = checkArguments.split("\\s+", 2);
                if (attribute.length != 2 || attribute[0].isBlank() || attribute[1].isBlank())
                    throw lineError(lineNumber, "CHECK ATTRIBUTE must be '<name> <value>'");

                yield new Command(lineNumber, CommandType.CHECK_ATTRIBUTE, attribute[0], attribute[1].trim(), 0, null, 0);
            }
            case "URL" -> command(lineNumber, CommandType.CHECK_URL, requireArgument(checkArguments, lineNumber, "CHECK URL"));
            default -> throw lineError(lineNumber, "Unsupported CHECK '" + tokens[0] + "'");
        };
    }

    private static Command currentCommand(int lineNumber, CommandType type, String arguments, boolean currentElement) {
        String keyword = type.name();
        requireNoArguments(arguments, lineNumber, keyword);
        requireCurrent(currentElement, lineNumber, keyword);
        return command(lineNumber, type);
    }

    private static Command currentCheck(int lineNumber, CommandType type, String arguments, boolean currentElement, String keyword) {
        requireNoArguments(arguments, lineNumber, keyword);
        requireCurrent(currentElement, lineNumber, keyword);
        return command(lineNumber, type);
    }

    private static Command durationCommand(int lineNumber, String arguments) {
        String duration = requireArgument(arguments, lineNumber, "WAIT");
        if (duration.indexOf(' ') >= 0 || duration.indexOf('\t') >= 0)
            throw lineError(lineNumber, "WAIT accepts exactly one duration");

        return new Command(lineNumber, CommandType.WAIT, null, null, parseDurationMillis(duration, lineNumber), null, 0);
    }

    private static Command selectorWaitCommand(int lineNumber, CommandType type, String arguments) {
        String configured = requireArgument(arguments, lineNumber, type.name());
        Matcher matcher = SELECTOR_TIMEOUT.matcher(configured);
        if (!matcher.matches())
            return command(lineNumber, type, configured);

        return new Command(lineNumber, type, matcher.group(1), null, parseDurationMillis(matcher.group(2), lineNumber), null, 0);
    }

    private static long parseDurationMillis(String configured, int lineNumber) {
        Matcher matcher = DURATION.matcher(configured);
        if (!matcher.matches())
            throw lineError(lineNumber, "Duration must be a positive integer followed by ms or s");

        try {
            long value = Long.parseLong(matcher.group(1));
            long millis = "s".equalsIgnoreCase(matcher.group(2)) ? Math.multiplyExact(value, 1_000) : value;
            if (millis <= 0)
                throw lineError(lineNumber, "Duration must be positive");

            return millis;
        } catch (ArithmeticException | NumberFormatException exception) {
            throw lineError(lineNumber, "Duration is too large");
        }
    }

    private static void execute(BrowserSession session, Command command, long elementTimeoutMillis) throws InterruptedException {
        long timeout = command.durationMillis() > 0 ? command.durationMillis() : elementTimeoutMillis;
        switch (command.type()) {
            case QUERY -> session.query(command.first(), elementTimeoutMillis);
            case USE -> session.click(elementTimeoutMillis);
            case DOUBLE_USE -> session.doubleClick(elementTimeoutMillis);
            case HOVER -> session.hover(elementTimeoutMillis);
            case FOCUS -> session.focus(elementTimeoutMillis);
            case SELECT -> session.select(command.first(), elementTimeoutMillis);
            case WAIT -> Thread.sleep(command.durationMillis());
            case WAIT_VISIBLE -> session.waitVisible(command.first(), timeout);
            case WAIT_HIDDEN -> session.waitHidden(command.first(), timeout);
            case CHECK_TEXT -> session.check(() -> session.currentText().contains(command.first()), elementTimeoutMillis);
            case CHECK_COUNT -> session.check(() -> command.countOperator().matches(session.currentCount(), command.count()), elementTimeoutMillis);
            case CHECK_ENABLED -> session.check(session::currentEnabled, elementTimeoutMillis);
            case CHECK_DISABLED -> session.check(() -> !session.currentEnabled(), elementTimeoutMillis);
            case CHECK_CHECKED -> session.check(session::currentChecked, elementTimeoutMillis);
            case CHECK_UNCHECKED -> session.check(() -> !session.currentChecked(), elementTimeoutMillis);
            case CHECK_ATTRIBUTE -> session.check(() -> Objects.equals(command.second(), session.currentAttribute(command.first())), elementTimeoutMillis);
            case CHECK_URL -> session.checkUrl(command.first(), elementTimeoutMillis);
            case RELOAD -> throw new IllegalStateException("RELOAD must be executed by the navigation path");
        }
    }

    private static AlertResult navigationWarning(AlertExecutionContext context, Map<String, Object> statusMessage, Navigation navigation, long durationMillis, long timeoutMillis, Command command, String endpoint) {
        if (durationMillis > timeoutMillis)
            return warn(context, statusMessage, command == null ? "loadTimeout" : "reloadTimeout", command, command == null ? null : "RELOAD", endpoint);

        if (navigation.statusCode() != null && navigation.statusCode() >= 400)
            return warn(context, statusMessage, "httpStatus", command, command == null ? null : "RELOAD", endpoint);

        return null;
    }

    private static void updateNavigationStatus(Map<String, Object> statusMessage, BrowserSession session, Navigation navigation, long durationMillis, String prefix) {
        statusMessage.put(prefix + "DurationMs", durationMillis);
        if (navigation.statusCode() != null)
            statusMessage.put(prefix + "StatusCode", navigation.statusCode());

        statusMessage.put("finalUrl", safeUrl(session.url()));
    }

    private static AlertResult warn(AlertExecutionContext context, Map<String, Object> statusMessage, String failureReason, Command command, String failedCommand, String endpoint) {
        statusMessage.put("failureReason", failureReason);
        if (command != null)
            statusMessage.put("failedLine", command.lineNumber());
        if (failedCommand != null)
            statusMessage.put("failedCommand", failedCommand);

        context.setState("endpoint=" + endpoint + ";status=WARN;failure=" + failureReason);
        return AlertResult.warn(statusMessage);
    }

    private static Map<String, Object> baseStatus(String endpoint, int loadTimeoutSeconds, int elementTimeoutSeconds, int commandCount) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("endpoint", endpoint);
        status.put("loadTimeoutSeconds", loadTimeoutSeconds);
        status.put("elementTimeoutSeconds", elementTimeoutSeconds);
        status.put("commandCount", commandCount);
        status.put("completedCommandCount", 0);
        status.put("checkedAt", Instant.now().toString());
        return status;
    }

    private static URI parseUrl(String configured) {
        try {
            URI uri = URI.create(requireText(configured, "url"));
            if (!uri.isAbsolute() || uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())))
                throw new IllegalArgumentException("url must be an absolute HTTP or HTTPS URL");

            return uri;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid url", exception);
        }
    }

    private static String safeUrl(String configured) {
        try {
            URI uri = URI.create(configured);
            if (uri.getHost() == null)
                return uri.getScheme() == null ? "unknown" : uri.getScheme() + ":";

            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getRawPath(), null, null).toString();
        } catch (IllegalArgumentException | URISyntaxException exception) {
            return "unknown";
        }
    }

    private static long timeoutMillis(int seconds, String parameter) {
        if (seconds <= 0)
            throw new IllegalArgumentException(parameter + " must be positive");

        return Math.multiplyExact((long) seconds, 1_000);
    }

    private static String requireText(String value, String parameter) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(parameter + " must not be blank");

        return value.trim();
    }

    private static String requireArgument(String arguments, int lineNumber, String command) {
        if (arguments.isBlank())
            throw lineError(lineNumber, command + " requires an argument");

        return arguments;
    }

    private static void requireNoArguments(String arguments, int lineNumber, String command) {
        if (!arguments.isBlank())
            throw lineError(lineNumber, command + " does not accept arguments");
    }

    private static void requireCurrent(boolean currentElement, int lineNumber, String command) {
        if (!currentElement)
            throw lineError(lineNumber, command + " requires a previous QUERY");
    }

    private static IllegalArgumentException lineError(int lineNumber, String message) {
        return new IllegalArgumentException("Line " + lineNumber + ": " + message);
    }

    private static Command command(int lineNumber, CommandType type) {
        return new Command(lineNumber, type, null, null, 0, null, 0);
    }

    private static Command command(int lineNumber, CommandType type, String first) {
        return new Command(lineNumber, type, first, null, 0, null, 0);
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    private static boolean isPlaywrightTimeout(RuntimeException exception) {
        return "com.microsoft.playwright.TimeoutError".equals(exception.getClass().getName());
    }

    enum CommandType {
        QUERY,
        USE,
        DOUBLE_USE,
        HOVER,
        FOCUS,
        SELECT,
        WAIT,
        WAIT_VISIBLE,
        WAIT_HIDDEN,
        RELOAD,
        CHECK_TEXT,
        CHECK_COUNT,
        CHECK_ENABLED,
        CHECK_DISABLED,
        CHECK_CHECKED,
        CHECK_UNCHECKED,
        CHECK_ATTRIBUTE,
        CHECK_URL
    }

    enum CountOperator {
        EQUAL,
        NOT_EQUAL,
        GREATER,
        GREATER_OR_EQUAL,
        LOWER,
        LOWER_OR_EQUAL;

        static CountOperator parse(String configured) {
            return switch (configured) {
                case "=" -> EQUAL;
                case "!=" -> NOT_EQUAL;
                case ">" -> GREATER;
                case ">=" -> GREATER_OR_EQUAL;
                case "<" -> LOWER;
                case "<=" -> LOWER_OR_EQUAL;
                default -> throw new IllegalArgumentException("Unsupported count operator: " + configured);
            };
        }

        boolean matches(int actual, int expected) {
            return switch (this) {
                case EQUAL -> actual == expected;
                case NOT_EQUAL -> actual != expected;
                case GREATER -> actual > expected;
                case GREATER_OR_EQUAL -> actual >= expected;
                case LOWER -> actual < expected;
                case LOWER_OR_EQUAL -> actual <= expected;
            };
        }
    }

    record Command(int lineNumber, CommandType type, String first, String second, long durationMillis, CountOperator countOperator, int count) {

        String keyword() {
            return switch (type) {
                case CHECK_TEXT, CHECK_COUNT, CHECK_ENABLED, CHECK_DISABLED, CHECK_CHECKED, CHECK_UNCHECKED, CHECK_ATTRIBUTE, CHECK_URL -> "CHECK";
                default -> type.name();
            };
        }
    }

    record Navigation(Integer statusCode) {
    }

    @FunctionalInterface
    interface BrowserSessionFactory {
        BrowserSession open();
    }

    interface BrowserSession extends AutoCloseable {
        Navigation navigate(String url, long timeoutMillis);
        Navigation reload(long timeoutMillis);
        String url();
        void query(String selector, long timeoutMillis);
        void click(long timeoutMillis);
        void doubleClick(long timeoutMillis);
        void hover(long timeoutMillis);
        void focus(long timeoutMillis);
        void select(String value, long timeoutMillis);
        void waitVisible(String selector, long timeoutMillis);
        void waitHidden(String selector, long timeoutMillis);
        void check(BooleanSupplier condition, long timeoutMillis);
        void checkUrl(String glob, long timeoutMillis);
        String currentText();
        int currentCount();
        boolean currentEnabled();
        boolean currentChecked();
        String currentAttribute(String name);
        @Override void close();
    }

    private static final class PlaywrightBrowserSession implements BrowserSession {

        private final Playwright playwright;
        private final Browser browser;
        private final BrowserContext context;
        private final Page page;
        private Locator current;

        private PlaywrightBrowserSession(Playwright playwright, Browser browser, BrowserContext context, Page page) {
            this.playwright = playwright;
            this.browser = browser;
            this.context = context;
            this.page = page;
        }

        static BrowserSession open() {
            Playwright playwright = Playwright.create();
            try {
                Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
                BrowserContext context = browser.newContext(new Browser.NewContextOptions().setViewportSize(1440, 900));
                return new PlaywrightBrowserSession(playwright, browser, context, context.newPage());
            } catch (RuntimeException exception) {
                playwright.close();
                throw exception;
            }
        }

        @Override
        public Navigation navigate(String url, long timeoutMillis) {
            Response response = page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.LOAD).setTimeout((double) timeoutMillis));
            return navigation(response);
        }

        @Override
        public Navigation reload(long timeoutMillis) {
            current = null;
            Response response = page.reload(new Page.ReloadOptions().setWaitUntil(WaitUntilState.LOAD).setTimeout((double) timeoutMillis));
            return navigation(response);
        }

        @Override
        public String url() {
            return page.url();
        }

        @Override
        public void query(String selector, long timeoutMillis) {
            Locator visible = page.locator(selector).filter(new Locator.FilterOptions().setVisible(true));
            visible.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout((double) timeoutMillis));
            current = visible;
        }

        @Override
        public void click(long timeoutMillis) {
            current.click(new Locator.ClickOptions().setTimeout((double) timeoutMillis));
        }

        @Override
        public void doubleClick(long timeoutMillis) {
            current.dblclick(new Locator.DblclickOptions().setTimeout((double) timeoutMillis));
        }

        @Override
        public void hover(long timeoutMillis) {
            current.hover(new Locator.HoverOptions().setTimeout((double) timeoutMillis));
        }

        @Override
        public void focus(long timeoutMillis) {
            current.focus(new Locator.FocusOptions().setTimeout((double) timeoutMillis));
        }

        @Override
        public void select(String value, long timeoutMillis) {
            current.selectOption(value, new Locator.SelectOptionOptions().setTimeout((double) timeoutMillis));
        }

        @Override
        public void waitVisible(String selector, long timeoutMillis) {
            page.locator(selector).filter(new Locator.FilterOptions().setVisible(true)).first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout((double) timeoutMillis));
        }

        @Override
        public void waitHidden(String selector, long timeoutMillis) {
            page.locator(selector).first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN).setTimeout((double) timeoutMillis));
        }

        @Override
        public void check(BooleanSupplier condition, long timeoutMillis) {
            long deadlineNanos = System.nanoTime() + Math.multiplyExact(timeoutMillis, 1_000_000);
            do {
                if (condition.getAsBoolean())
                    return;

                long remainingMillis = Math.max(1, (deadlineNanos - System.nanoTime()) / 1_000_000);
                page.waitForTimeout(Math.min(POLL_INTERVAL_MILLIS, remainingMillis));
            } while (System.nanoTime() < deadlineNanos);
            throw new IllegalStateException("Condition was not satisfied before timeout");
        }

        @Override
        public void checkUrl(String glob, long timeoutMillis) {
            page.waitForURL(glob, new Page.WaitForURLOptions().setTimeout((double) timeoutMillis));
        }

        @Override
        public String currentText() {
            return current.innerText();
        }

        @Override
        public int currentCount() {
            return current.count();
        }

        @Override
        public boolean currentEnabled() {
            return current.isEnabled();
        }

        @Override
        public boolean currentChecked() {
            return current.isChecked();
        }

        @Override
        public String currentAttribute(String name) {
            return current.getAttribute(name);
        }

        @Override
        public void close() {
            context.close();
            browser.close();
            playwright.close();
        }

        private static Navigation navigation(Response response) {
            return new Navigation(response == null ? null : response.status());
        }
    }
}
