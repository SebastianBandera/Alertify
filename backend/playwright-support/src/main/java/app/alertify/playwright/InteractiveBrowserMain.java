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
import java.util.function.Consumer;

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
import tools.jackson.databind.node.ObjectNode;

/** Per-session child process. Stdout is a protobuf pipe, never a diagnostic log. */
public final class InteractiveBrowserMain implements AutoCloseable {
    private final JsonMapper json = JsonMapper.builder().build();
    private final Consumer<WizardEvent> output;
    private final ArrayBlockingQueue<WizardRequest> requests = new ArrayBlockingQueue<>(16);
    private final List<String> sensitive = new ArrayList<>();
    private PlaywrightBrowserSession session;
    private BrowserPageInspection inspection;
    private BrowserSecretProtection protection;
    private SecretValueSanitizer.Protection filter = SecretValueSanitizer.protection(List.of());
    private boolean filterDirty;
    private String publishedUrl;
    private String activeEngine;
    private boolean capturing;
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

    public InteractiveBrowserMain() { this(null); }
    InteractiveBrowserMain(Consumer<WizardEvent> output) { this.output = output; }

    public static void main(String[] arguments) throws Exception {
        new InteractiveBrowserMain().run();
    }

    private void run() throws Exception {
        Thread.ofVirtual().start(() -> {
            try {
                WizardRequest request;
                while ((request = WizardRequest.parseDelimitedFrom(System.in)) != null) {
                    if (request.getOperation().equals("PROCEDURE_REPLY")) {
                        procedureReply(request);
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
                    captureIfDue();
                    session.page().waitForTimeout(10);
                }
            }
        } finally {
            closeBrowser();
        }
    }

    void procedureReply(WizardRequest request) {
        var reply = valueReplies.remove(request.getRequestId());
        if (reply != null)
            reply.complete(json.readTree(request.getJson()));
    }

    void handle(WizardRequest request) throws Exception {
        try {
            JsonNode payload = json.readTree(request.getJson());
            if (session != null && List.of("OBSERVE", "FIND", "SELECT", "RESOLVE_REF", "CHECK_SELECTOR").contains(request.getOperation()))
                rememberPasswords();

            switch (request.getOperation()) {
                case "OPEN", "RESET" -> {
                    settings = payload;
                    open(text(payload, "engine", "chromium"));
                    result(request, Map.of("completed", 0, "status", "READY"));
                }
                case "OBSERVE" -> result(request, observe(payload));
                case "FIND" -> result(request, find(payload));
                case "CHECK_SELECTOR" -> result(request, checkSelector(payload));
                case "SELECT" -> result(request, select(payload));
                case "RESOLVE_REF" -> {
                    requireBrowser();
                    String reference = payload.path("ref").asString();
                    if (!reference.matches("[a-zA-Z0-9_-]{1,64}"))
                        throw new IllegalArgumentException("Invalid ARIA reference");

                    Locator locator = session.page().locator("aria-ref=" + reference);
                    String selector = BrowserPageInspection.selector(locator);
                    if (selector == null)
                        throw new SelectionFailure("NO_SELECTION");

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
            if (exception instanceof SelectionFailure selection)
                failure.put("failureCode", selection.code);

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
        try {
            Page page = session.page();
            page.setDefaultTimeout(elementTimeout());
            inspection = new BrowserPageInspection(page, this::captureIfDue);
            protection = new BrowserSecretProtection(page);
            var navigation = session.navigate(uri.toString(), loadTimeout());
            String type = navigation.contentType().split(";", 2)[0].strip().toLowerCase(java.util.Locale.ROOT);
            if (navigation.statusCode() == null || navigation.statusCode() < 200 || navigation.statusCode() >= 300 || !(type.equals("text/html") || type.equals("application/xhtml+xml")))
                throw new IllegalArgumentException("The URL must return successful HTML content");

            activeEngine = engine;
            snapshotId++;
        } catch (RuntimeException failure) {
            closeBrowser();
            throw failure;
        }
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
            try {
                open(engine.asString());
                execute(request, payload, false);
                results.add(Map.of("browser", engine.asString(), "passed", true, "completed", completed));
            } catch (Exception exception) {
                passed = false;
                results.add(Map.of("browser", engine.asString(), "passed", false, "completed", completed, "error", sanitize(exception.getMessage())));
            }
        }
        result(request, Map.of("passed", passed && !results.isEmpty(), "browsers", results, "activeEngine", activeEngine == null ? "" : activeEngine));
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
            session.page().waitForCondition(reply::isDone, new Page.WaitForConditionOptions().setTimeout(175_000));
            JsonNode value = reply.get(0, TimeUnit.SECONDS);
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
        String snapshot = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            List<String> before = rememberPasswords();
            String candidate = session.page().locator(scope).ariaSnapshot(new Locator.AriaSnapshotOptions().setMode(AriaSnapshotMode.AI).setDepth(32).setBoxes(true));
            if (before.equals(rememberPasswords())) {
                snapshot = candidate;
                break;
            }
        }
        if (snapshot == null)
            throw new IllegalStateException("Password controls changed while producing the protected observation");

        snapshotId++;
        String safe = sanitize(snapshot);
        List<Map<String, Object>> controls = new ArrayList<>();
        Locator interactive = session.page().locator(scope).locator("input:not([type=hidden]),textarea,select,button,a[href],[role=button],[role=textbox]")
                .filter(new Locator.FilterOptions().setVisible(true));
        for (int index = 0; index < Math.min(interactive.count(), 40); index++) {
            Locator element = interactive.nth(index);
            String selector = BrowserPageInspection.selector(element);
            if (selector == null || session.page().locator(selector).filter(new Locator.FilterOptions().setVisible(true)).count() != 1)
                continue;

            // Accessible labels are not necessarily aria-label attributes. Never read control values.
            Object metadata = element.evaluate("element => ({tag: element.tagName.toLowerCase(), type: element.getAttribute('type') || '', label: (element.getAttribute('aria-label') || element.labels?.[0]?.innerText || element.getAttribute('placeholder') || element.innerText || '').slice(0, 200)})");
            controls.add(Map.of("selector", sanitize(selector), "metadata", filter().sanitize(json.valueToTree(metadata))));
        }
        return Map.of("snapshot", safe.substring(0, Math.min(safe.length(), 40_000)), "controls", controls, "snapshotId", snapshotId, "completed", completed, "dirty", dirty);
    }

    private List<Map<String, Object>> find(JsonNode payload) {
        requireBrowser();
        rememberPasswords();
        String text = payload.path("text").asString();
        if (text.isBlank() || text.length() > 300)
            throw new IllegalArgumentException("Search text must contain 1-300 characters");

        List<Map<String, Object>> found = new ArrayList<>();
        Locator matches = session.page().getByText(text, new Page.GetByTextOptions().setExact(true)).filter(new Locator.FilterOptions().setVisible(true));
        for (int index = 0; index < Math.min(matches.count(), 15); index++) {
            Locator element = matches.nth(index);
            String selector = BrowserPageInspection.selector(element);
            if (selector == null)
                continue;

            var box = element.boundingBox();
            found.add(Map.of("selector", sanitize(selector), "text", sanitize(element.innerText()), "x", box == null ? 0 : box.x, "y", box == null ? 0 : box.y));
        }
        return found;
    }

    private Map<String, Object> checkSelector(JsonNode payload) {
        requireBrowser();
        String selector = payload.path("selector").asString();
        if (selector.isBlank() || selector.length() > 4000)
            throw new IllegalArgumentException("Supply a selector of 1-4000 characters");

        Locator all = session.page().locator(selector);
        Locator visible = all.filter(new Locator.FilterOptions().setVisible(true));
        int totalCount = all.count();
        int visibleCount = visible.count();
        List<JsonNode> matches = new ArrayList<>();
        for (int index = 0; index < Math.min(visibleCount, 10); index++) {
            Locator element = visible.nth(index);
            Object metadata = element.evaluate("""
                    element => {
                      const regions = [];
                      for (let parent = element.parentElement; parent && regions.length < 4; parent = parent.parentElement) {
                        if (parent.matches('header,main,footer,nav,form,section,[role=navigation],[role=banner],[role=contentinfo]'))
                          regions.push({tag: parent.tagName.toLowerCase(), id: parent.id, label: parent.getAttribute('aria-label') || ''});
                      }
                      return {tag: element.tagName.toLowerCase(), label: (element.getAttribute('aria-label') || element.labels?.[0]?.innerText || element.getAttribute('placeholder') || element.innerText || '').slice(0, 200),
                        href: element.getAttribute('href') || '', regions};
                    }
                    """);
            var match = (ObjectNode) json.valueToTree(metadata);
            String verified = BrowserPageInspection.selector(element);
            if (verified != null) match.put("selector", verified);

            matches.add(filter().sanitize(match));
        }
        return Map.of("selector", sanitize(selector), "totalCount", totalCount, "visibleCount", visibleCount, "ambiguous", visibleCount > 1,
                "matches", matches, "truncated", visibleCount > matches.size(), "completed", completed, "dirty", dirty);
    }

    private Map<String, Object> select(JsonNode payload) throws Exception {
        requireBrowser();
        // A stale frame must never silently select a different element after navigation.
        if (!java.util.Objects.equals(frameRevisions.get(payload.path("frameId").asLong()), snapshotId)) {
            capture("");
            throw new SelectionFailure("STALE_SELECTION");
        }
        Map<String, Object> selected = inspection.select(payload.path("frameId").asLong(), payload.path("x").asDouble(), payload.path("y").asDouble());
        if (selected.containsKey("error")) {
            capture("");
            throw new SelectionFailure(selected.get("error").toString());
        }
        String selector = selected.get("selector").toString();
        if (!selector.equals(sanitize(selector)))
            throw new SelectionFailure("NO_SELECTION");

        return Map.of("selector", selector, "snapshotId", snapshotId);
    }

    private void capture(String requestId) throws Exception {
        if (session == null || inspection == null || capturing)
            return;

        capturing = true;
        lastCapture = System.nanoTime();
        try {
            rememberPasswords();
            publishUrl();
            List<Locator> masks = new ArrayList<>();
            if (!sensitive.isEmpty())
                masks.addAll(protection.componentAndFoldedTextMasks(filter().base64Values()));

            for (Frame frame : session.page().frames()) {
                masks.add(frame.locator("input[type=password]"));
                if (!sensitive.isEmpty()) {
                    // Canvas, images and frames cannot be reliably inspected for reflected secret pixels.
                    masks.add(frame.locator("canvas,img,video,iframe,svg,input,textarea,[contenteditable]"));
                    if (filter().maskPattern() != null)
                        masks.add(frame.getByText(filter().maskPattern()));
                }
            }
            var options = new Page.ScreenshotOptions().setType(ScreenshotType.JPEG).setQuality(65).setMask(masks).setTimeout(2000);
            if (!sensitive.isEmpty())
                options.setStyle("*::before, *::after { content: none !important; } * { background-image: none !important; }");

            long nextFrame = frameId + 1;
            inspection.beginFrame(nextFrame);
            byte[] image = session.page().screenshot(options);
            inspection.finishFrame(nextFrame);
            frameId = nextFrame;
            frameRevisions.put(frameId, snapshotId);
            while (frameRevisions.size() > 10) frameRevisions.remove(frameRevisions.keySet().iterator().next());

            send(WizardEvent.newBuilder().setRequestId(requestId).setType(requestId.isEmpty() ? "FRAME" : "IMAGE")
                    .setImage(ByteString.copyFrom(image)).setFrameId(frameId).build());
        } catch (RuntimeException exception) {
            // No raw fallback: a failed mask/capture must never release an unprotected frame.
            if (!requestId.isEmpty())
                throw new IllegalStateException("A protected screenshot could not be produced");
        } finally {
            capturing = false;
        }
    }

    private void captureIfDue() {
        if (capturing || session == null || System.nanoTime() - lastCapture < 200_000_000L)
            return;

        try { capture(""); }
        catch (Exception failure) { inputClosed = true; }
    }

    private void remember(String value) {
        if (value == null || value.isEmpty() || sensitive.contains(value))
            return;

        sensitive.add(value);
        filterDirty = true;
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

    private SecretValueSanitizer.Protection filter() {
        if (filterDirty) {
            filter = SecretValueSanitizer.protection(sensitive);
            filterDirty = false;
        }
        return filter;
    }

    private List<String> rememberPasswords() {
        List<String> values = protection.passwords();
        values.forEach(this::remember);
        return values;
    }

    private void publishUrl() throws Exception {
        String current;
        try {
            URI uri = URI.create(session.url());
            current = uri.getHost() == null ? "" : uri.getScheme() + "://" + uri.getRawAuthority().substring(uri.getRawAuthority().lastIndexOf('@') + 1)
                    + (uri.getRawPath() == null ? "" : uri.getRawPath()) + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()) + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment());
        } catch (IllegalArgumentException failure) { current = ""; }
        current = sanitize(current);
        if (!java.util.Objects.equals(current, publishedUrl)) {
            publishedUrl = current;
            send(WizardEvent.newBuilder().setType("PAGE_STATE").setJson(json.writeValueAsString(Map.of("currentUrl", current))).build());
        }
    }

    private String sanitize(String text) { return filter().sanitize(text == null ? "" : text); }
    private long loadTimeout() { return settings.path("loadTimeoutSeconds").asInt(10) * 1000L; }
    private long elementTimeout() { return settings.path("elementTimeoutSeconds").asInt(5) * 1000L; }
    private static String text(JsonNode value, String name, String fallback) { return value.path(name).asString(fallback); }

    private void result(WizardRequest request, Object value) throws Exception {
        JsonNode document = json.valueToTree(value);
        JsonNode safe = filter().sanitize(document);
        if (request.getOperation().equals("TEST")) {
            // Engine identities are declared metadata, never resolved values.
            ((ObjectNode) safe).set("activeEngine", document.path("activeEngine"));
            for (int index = 0; index < document.path("browsers").size(); index++)
                ((ObjectNode) safe.path("browsers").get(index)).set("browser", document.path("browsers").get(index).path("browser"));
        }
        send(WizardEvent.newBuilder().setRequestId(request.getRequestId()).setType("RESULT").setJson(json.writeValueAsString(safe)).build());
    }

    private void send(WizardEvent event) throws Exception {
        if (output != null) {
            output.accept(event);
            return;
        }
        event.writeDelimitedTo(System.out);
        System.out.flush();
    }

    private void requireBrowser() {
        if (session == null)
            throw new IllegalArgumentException("Open a browser first");
    }

    private void closeBrowser() {
        PlaywrightBrowserSession closing = session;
        session = null;
        inspection = null;
        protection = null;
        publishedUrl = null;
        activeEngine = null;
        frameRevisions.clear();
        if (closing != null)
            try { closing.close(); } catch (RuntimeException ignored) { /* The supervisor still owns the process tree. */ }
    }

    @Override public void close() { closeBrowser(); }

    private static final class SelectionFailure extends IllegalArgumentException {
        private final String code;

        private SelectionFailure(String code) {
            super(code.equals("NO_SELECTION") ? "Select an element on the page" : "The selected element changed; select it again in the current image");
            this.code = code.equals("NO_SELECTION") ? "NO_SELECTION" : "STALE_SELECTION";
        }
    }
}
