package app.alertify.playwright;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.google.protobuf.ByteString;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaSnapshotMode;
import com.microsoft.playwright.options.ScreenshotType;
import app.alertify.alerts.AlertExecutionContext;
import app.alertify.alerts.AlertExecutionValue;
import app.alertify.alerts.AlertExecutionValueSource;
import app.alertify.playwright.PlaywrightPageEngine.BrowserEngine;
import app.alertify.playwright.PlaywrightPageEngine.Command;
import app.alertify.playwright.PlaywrightPageEngine.CommandType;
import app.alertify.playwright.PlaywrightPageEngine.PlaywrightBrowserSession;
import app.alertify.playwright.PlaywrightPageEngine.ScriptState;
import app.alertify.worker.contract.SecretValueSanitizer;
import app.alertify.worker.wizard.grpc.WizardEvent;
import app.alertify.worker.wizard.grpc.WizardRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Per-session child process. Stdout is a protobuf pipe, never a diagnostic log. */
public final class InteractiveBrowserMain {
    private final JsonMapper json = JsonMapper.builder().build();
    private final ArrayBlockingQueue<WizardRequest> requests = new ArrayBlockingQueue<>(16);
    private final List<String> sensitive = new ArrayList<>();
    private PlaywrightBrowserSession session;
    private JsonNode settings;
    private int completed;
    private String appliedSteps = "";
    private boolean dirty;
    private long frameId;
    private long snapshotId;
    private long lastCapture;
    private final Map<Long, Long> frameRevisions = new java.util.LinkedHashMap<>();
    private volatile boolean inputClosed;
    private ScriptState scriptState = new ScriptState();
    private final ConcurrentMap<String, CompletableFuture<JsonNode>> valueReplies = new ConcurrentHashMap<>();

    public static void main(String[] arguments) throws Exception {
        new InteractiveBrowserMain().run();
    }

    private void run() throws Exception {
        Thread.ofVirtual().start(() -> {
            try {
                WizardRequest request;
                while ((request = WizardRequest.parseDelimitedFrom(System.in)) != null) {
                    if (request.getOperation().equals("PROCEDURE_REPLY")) {
                        var reply = valueReplies.remove(request.getRequestId());
                        if (reply != null) reply.complete(json.readTree(request.getJson()));
                    } else requests.put(request);
                }
            } catch (Exception ignored) {
                // The supervisor owns cleanup after a transport failure.
            } finally {
                inputClosed = true;
                valueReplies.values().forEach(reply -> reply.completeExceptionally(new IllegalStateException("Browser stream closed")));
            }
        });
        try {
            while (!inputClosed) {
                WizardRequest request = requests.poll(10, TimeUnit.MILLISECONDS);
                if (request != null) {
                    if (request.getOperation().equals("CLOSE"))
                        return;

                    handle(request);
                }
                if (session != null) {
                    if (System.nanoTime() - lastCapture >= 200_000_000L)
                        capture("");

                    session.page().waitForTimeout(10);
                }
            }
        } finally {
            closeBrowser();
        }
    }

    private void handle(WizardRequest request) throws Exception {
        try {
            JsonNode payload = json.readTree(request.getJson());
            switch (request.getOperation()) {
                case "OPEN", "RESET" -> {
                    settings = payload;
                    open(text(payload, "engine", "chromium"));
                    result(request, Map.of("completed", 0, "status", "READY"));
                }
                case "OBSERVE" -> result(request, observe(payload));
                case "FIND" -> result(request, find(payload));
                case "SELECT" -> result(request, select(payload));
                case "RESOLVE_REF" -> {
                    requireBrowser();
                    String reference = payload.path("ref").asString();
                    if (!reference.matches("[a-zA-Z0-9_-]{1,64}"))
                        throw new IllegalArgumentException("Invalid ARIA reference");

                    Locator locator = session.page().locator("aria-ref=" + reference);
                    String selector = (String) locator.evaluate(SELECTOR_SCRIPT);
                    result(request, Map.of("selector", sanitize(selector)));
                }
                case "SCROLL" -> {
                    session.page().mouse().wheel(0, payload.path("delta").asDouble());
                    snapshotId++;
                    result(request, Map.of("status", "READY"));
                }
                case "SCREENSHOT" -> capture(request.getRequestId());
                case "EXECUTE" -> execute(request, payload);
                case "TEST" -> test(request, payload);
                default -> throw new IllegalArgumentException("Unsupported operation");
            }
        } catch (Exception exception) {
            String error = exception instanceof IllegalArgumentException ? exception.getMessage() : "Browser operation failed or timed out";
            var failure = json.createObjectNode().put("error", sanitize(error)).put("completed", completed).put("dirty", dirty);
            if (exception instanceof BrowserStepFailure step) {
                failure.put("failedLine", step.line).put("failedCommand", step.command).put("failureCode", step.code);
            }
            send(WizardEvent.newBuilder().setRequestId(request.getRequestId()).setType("ERROR")
                    .setJson(json.writeValueAsString(failure)).build());
        }
    }

    private void open(String engine) {
        closeBrowser();
        completed = 0;
        appliedSteps = "";
        scriptState = new ScriptState();
        dirty = false;
        URI uri = URI.create(settings.path("url").asString());
        if (uri.getHost() == null || uri.getUserInfo() != null || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https")))
            throw new IllegalArgumentException("An HTTP or HTTPS URL without embedded credentials is required");

        session = PlaywrightBrowserSession.open(BrowserEngine.valueOf(engine.toUpperCase(java.util.Locale.ROOT)));
        Page page = session.page();
        page.setDefaultTimeout(elementTimeout());
        // This request executes in the same worker/network as the actual browser.
        var response = page.context().request().get(uri.toString(), com.microsoft.playwright.options.RequestOptions.create().setTimeout(loadTimeout()));
        try {
            String type = response.headers().getOrDefault("content-type", "").toLowerCase(java.util.Locale.ROOT);
            if (!response.ok() || !(type.startsWith("text/html") || type.startsWith("application/xhtml+xml")))
                throw new IllegalArgumentException("The URL must return successful HTML content");
        } finally {
            response.dispose();
        }
        page.context().clearCookies();
        var navigation = session.navigate(uri.toString(), loadTimeout());
        if (navigation.statusCode() != null && navigation.statusCode() >= 400)
            throw new IllegalArgumentException("The browser received an unsuccessful HTTP response");

        snapshotId++;
    }

    private void execute(WizardRequest request, JsonNode payload) throws Exception {
        execute(request, payload, true);
    }

    private void execute(WizardRequest request, JsonNode payload, boolean terminal) throws Exception {
        requireBrowser();
        String steps = payload.path("steps").asString();
        if (dirty || (!appliedSteps.isEmpty() && !steps.equals(appliedSteps) && !steps.startsWith(appliedSteps + "\n")))
            throw new IllegalArgumentException("Restart and replay the script after changing executed steps or a failed action");

        List<Command> commands = PlaywrightPageEngine.parseCommands(steps);
        if (commands.size() > 500)
            throw new IllegalArgumentException("The script exceeds 500 commands");

        AlertExecutionContext context = context(payload);
        dirty = true; // Fail closed if an action partially changes the remote site.
        for (int index = completed; index < commands.size(); index++) {
            Command command = commands.get(index);
            try {
                if (command.type() == CommandType.RELOAD) {
                    var navigation = session.reload(loadTimeout());
                    if (navigation.statusCode() != null && navigation.statusCode() >= 400)
                        throw new IllegalStateException("HTTP failure");
                } else if (command.type() == CommandType.WAIT) {
                    long until = System.nanoTime() + command.durationMillis() * 1_000_000;
                    while (System.nanoTime() < until) {
                        capture("");
                        session.page().waitForTimeout(Math.min(200, command.durationMillis()));
                    }
                } else {
                    PlaywrightPageEngine.execute(context, session, command, elementTimeout(), scriptState);
                }
            } catch (Exception exception) {
                throw new BrowserStepFailure(command, exception);
            }
            completed = index + 1;
            snapshotId++;
            send(WizardEvent.newBuilder().setRequestId(request.getRequestId()).setType("PROGRESS")
                    .setJson(json.writeValueAsString(Map.of("line", command.lineNumber(), "completed", completed))).build());
            capture("");
        }
        appliedSteps = steps;
        dirty = false;
        if (terminal)
            result(request, Map.of("completed", completed, "status", "READY"));
    }

    private void test(WizardRequest request, JsonNode payload) throws Exception {
        settings = payload;
        List<Map<String, Object>> results = new ArrayList<>();
        boolean passed = true;
        for (JsonNode engine : payload.path("browsers")) {
            open(engine.asString());
            try {
                execute(request, payload, false);
                results.add(Map.of("browser", engine.asString(), "passed", true, "completed", completed));
            } catch (Exception exception) {
                passed = false;
                results.add(Map.of("browser", engine.asString(), "passed", false, "completed", completed, "error", sanitize(exception.getMessage())));
            }
        }
        result(request, Map.of("passed", passed && !results.isEmpty(), "browsers", results));
    }

    private AlertExecutionContext context(JsonNode payload) {
        List<AlertExecutionValue> values = new ArrayList<>();
        for (JsonNode value : payload.path("values")) {
            String raw = value.path("value").asString();
            if (!value.path("source").asString().equals("PROCEDURE")) remember(raw);
            values.add(new AlertExecutionValue(AlertExecutionValueSource.valueOf(value.path("source").asString()), value.path("name").asString(), raw));
        }
        return new AlertExecutionContext("", Map.of(), values, this::procedureValue, this::remember);
    }

    private String procedureValue(String token) {
        String id = java.util.UUID.randomUUID().toString();
        var reply = new CompletableFuture<JsonNode>();
        valueReplies.put(id, reply);
        try {
            send(WizardEvent.newBuilder().setType("PROCEDURE_CALL").setRequestId(id).setJson(json.writeValueAsString(Map.of("token", token))).build());
            JsonNode value = reply.get(175, TimeUnit.SECONDS);
            if (value.has("error"))
                throw new IllegalArgumentException(value.path("error").asString());

            return value.path("value").asString();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("Procedure call was interrupted");
        } catch (ExecutionException | TimeoutException failure) {
            throw new IllegalArgumentException("Procedure call failed or timed out");
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalArgumentException("Procedure transport failed");
        } finally { valueReplies.remove(id); }
    }

    private Map<String, Object> observe(JsonNode payload) {
        requireBrowser();
        String scope = payload.path("selector").asString("body");
        String snapshot = session.page().locator(scope).ariaSnapshot(new Locator.AriaSnapshotOptions().setMode(AriaSnapshotMode.AI).setDepth(32).setBoxes(true));
        snapshotId++;
        String safe = sanitize(snapshot);
        List<Map<String, Object>> controls = new ArrayList<>();
        Locator interactive = session.page().locator(scope).locator("input:not([type=hidden]),textarea,select,button,a[href],[role=button],[role=textbox]")
                .filter(new Locator.FilterOptions().setVisible(true));
        for (int index = 0; index < Math.min(interactive.count(), 40); index++) {
            Locator element = interactive.nth(index);
            String selector = (String) element.evaluate(SELECTOR_SCRIPT);
            if (session.page().locator(selector).filter(new Locator.FilterOptions().setVisible(true)).count() != 1)
                continue;

            // Accessible labels are not necessarily aria-label attributes. Never read control values.
            Object metadata = element.evaluate("element => ({tag: element.tagName.toLowerCase(), type: element.getAttribute('type') || '', label: (element.getAttribute('aria-label') || element.labels?.[0]?.innerText || element.getAttribute('placeholder') || element.innerText || '').slice(0, 200)})");
            controls.add(Map.of("selector", sanitize(selector), "metadata", SecretValueSanitizer.sanitize(json.valueToTree(metadata), sensitive)));
        }
        return Map.of("snapshot", safe.substring(0, Math.min(safe.length(), 40_000)), "controls", controls, "snapshotId", snapshotId, "completed", completed, "dirty", dirty);
    }

    private List<Map<String, Object>> find(JsonNode payload) {
        requireBrowser();
        String text = payload.path("text").asString();
        if (text.isBlank() || text.length() > 300)
            throw new IllegalArgumentException("Search text must contain 1-300 characters");

        List<Map<String, Object>> found = new ArrayList<>();
        Locator matches = session.page().getByText(text, new Page.GetByTextOptions().setExact(true)).filter(new Locator.FilterOptions().setVisible(true));
        for (int index = 0; index < Math.min(matches.count(), 15); index++) {
            Locator element = matches.nth(index);
            String selector = (String) element.evaluate(SELECTOR_SCRIPT);
            var box = element.boundingBox();
            found.add(Map.of("selector", sanitize(selector), "text", sanitize(element.innerText()), "x", box == null ? 0 : box.x, "y", box == null ? 0 : box.y));
        }
        return found;
    }

    private Map<String, Object> select(JsonNode payload) {
        requireBrowser();
        // A stale frame must never silently select a different element after navigation.
        if (!java.util.Objects.equals(frameRevisions.get(payload.path("frameId").asLong()), snapshotId))
            throw new IllegalArgumentException("The image changed; select the element again");

        Object selector = session.page().evaluate("""
                ({x, y}) => {
                  let element = document.elementFromPoint(x, y);
                  if (!element) return null;

                  element = element.closest('button,a,input,textarea,select,[role]') || element;
                  if (element.id) return '#' + CSS.escape(element.id);

                  const testId = element.getAttribute('data-testid');
                  if (testId) return '[data-testid=' + JSON.stringify(testId) + ']';

                  const segments = [];
                  while (element && element !== document.body) {
                    let selector = element.tagName.toLowerCase();
                    const siblings = element.parentElement ? [...element.parentElement.children].filter(e => e.tagName === element.tagName) : [];
                    if (siblings.length > 1) selector += ':nth-of-type(' + (siblings.indexOf(element) + 1) + ')';

                    segments.unshift(selector);
                    element = element.parentElement;
                  }
                  return 'body > ' + segments.join(' > ');
                }
                """, Map.of("x", payload.path("x").asDouble(), "y", payload.path("y").asDouble()));
        if (selector == null)
            throw new IllegalArgumentException("No element was found at that position");

        return Map.of("selector", sanitize(selector.toString()), "snapshotId", snapshotId);
    }

    private void capture(String requestId) throws Exception {
        lastCapture = System.nanoTime();
        if (session == null)
            return;

        try {
            List<Locator> masks = new ArrayList<>();
            for (Frame frame : session.page().frames()) {
                masks.add(frame.locator("input[type=password]"));
                if (!sensitive.isEmpty()) {
                    // Canvas, images and frames cannot be reliably inspected for reflected secret pixels.
                    masks.add(frame.locator("canvas,img,video,iframe,svg,input,textarea,[contenteditable]"));
                    for (String value : sensitive)
                        masks.add(frame.getByText(value));
                }
            }
            var options = new Page.ScreenshotOptions().setType(ScreenshotType.JPEG).setQuality(65).setMask(masks).setTimeout(2000);
            if (!sensitive.isEmpty())
                options.setStyle("*::before, *::after { content: none !important; } * { background-image: none !important; }");

            byte[] image = session.page().screenshot(options);
            frameId++;
            frameRevisions.put(frameId, snapshotId);
            while (frameRevisions.size() > 10) frameRevisions.remove(frameRevisions.keySet().iterator().next());

            send(WizardEvent.newBuilder().setRequestId(requestId).setType(requestId.isEmpty() ? "FRAME" : "IMAGE")
                    .setImage(ByteString.copyFrom(image)).setFrameId(frameId).build());
        } catch (RuntimeException exception) {
            // No raw fallback: a failed mask/capture must never release an unprotected frame.
            if (!requestId.isEmpty())
                throw new IllegalStateException("A protected screenshot could not be produced");
        }
    }

    private void remember(String value) {
        if (value == null || value.isEmpty() || sensitive.contains(value))
            return;

        sensitive.add(value);
        try {
            JsonNode node = json.readTree(value);
            collect(node);
        } catch (RuntimeException ignored) {
            // Plain text has already been registered.
        }
    }

    private void collect(JsonNode node) {
        if (node == null)
            return;

        if (node.isString()) {
            String text = node.asString();
            if (!text.isEmpty() && !sensitive.contains(text))
                sensitive.add(text);
        } else if (node.isContainer()) {
            node.forEach(this::collect);
        }
    }

    private String sanitize(String text) { return SecretValueSanitizer.sanitize(text == null ? "" : text, sensitive); }
    private long loadTimeout() { return settings.path("loadTimeoutSeconds").asInt(10) * 1000L; }
    private long elementTimeout() { return settings.path("elementTimeoutSeconds").asInt(5) * 1000L; }
    private static String text(JsonNode value, String name, String fallback) { return value.path(name).asString(fallback); }

    private void result(WizardRequest request, Object value) throws Exception {
        send(WizardEvent.newBuilder().setRequestId(request.getRequestId()).setType("RESULT").setJson(json.writeValueAsString(SecretValueSanitizer.sanitize(json.valueToTree(value), sensitive))).build());
    }

    private void send(WizardEvent event) throws Exception {
        event.writeDelimitedTo(System.out);
        System.out.flush();
    }

    private void requireBrowser() {
        if (session == null)
            throw new IllegalArgumentException("Open a browser first");
    }

    private void closeBrowser() {
        if (session != null) {
            try { session.close(); } catch (RuntimeException ignored) { /* The supervisor still owns the process tree. */ }
            session = null;
        }
    }

    private static final String SELECTOR_SCRIPT = """
            element => {
              if (element.id) return '#' + CSS.escape(element.id);

              const testId = element.getAttribute('data-testid');
              if (testId) return '[data-testid=' + JSON.stringify(testId) + ']';

              const segments = [];
              while (element && element !== document.body) {
                let selector = element.tagName.toLowerCase();
                const siblings = element.parentElement ? [...element.parentElement.children].filter(e => e.tagName === element.tagName) : [];
                if (siblings.length > 1) selector += ':nth-of-type(' + (siblings.indexOf(element) + 1) + ')';

                segments.unshift(selector);
                element = element.parentElement;
              }
              return 'body > ' + segments.join(' > ');
            }
            """;
}
