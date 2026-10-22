package app.alertify.playwright;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.sun.net.httpserver.HttpServer;

import app.alertify.playwright.PlaywrightPageEngine.BrowserEngine;
import app.alertify.playwright.PlaywrightPageEngine.PlaywrightBrowserSession;
import app.alertify.worker.wizard.grpc.WizardEvent;
import app.alertify.worker.wizard.grpc.WizardRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Run in a disposable browser-equipped container with ALERTIFY_BROWSER_TESTS=true. */
@EnabledIfEnvironmentVariable(named = "ALERTIFY_BROWSER_TESTS", matches = "true")
class InteractiveBrowserMainBrowserTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<String, AtomicInteger> REQUESTS = new ConcurrentHashMap<>();
    private static HttpServer server;
    private static String origin;
    private static final String LOGIN_USER = "fixture-user";
    private static final String LOGIN_PASSWORD = UUID.randomUUID().toString();
    private static final String LOGIN_STEPS = """
            QUERY #username
            FILL SECRET "Fixture" POINTER "/username"
            QUERY #password
            FILL SECRET "Fixture" POINTER "/password"
            QUERY #login
            USE
            WAIT_VISIBLE #authenticated 10s
            QUERY #authenticated
            CHECK TEXT Área autenticada
            QUERY #logout
            USE
            WAIT_VISIBLE #username 10s
            QUERY main
            CHECK TEXT Sesión cerrada
            """;
    private static final String HTML = """
            <!doctype html><html><head><style>
            body{margin:0;height:100vh}#before,#after{position:absolute;left:20px;top:100px;width:180px;height:40px}
            #password{position:absolute;left:20px;top:20px;width:200px;height:30px}
            #input{position:absolute;left:20px;top:180px}h1{position:absolute;left:20px;top:250px;margin:0}
            #clock{position:absolute;left:600px;top:20px}#ready{position:absolute;left:20px;top:350px}
            </style></head><body><input id=password type=password value=fixture-canary>
            <button id=before>Run</button><span id=clock>Clock</span><textarea id=input></textarea><h1>Public content</h1>
            <div id=closed style="position:absolute;left:20px;top:450px;width:500px;height:40px"></div>
            <pre id=folded style="position:absolute;left:20px;top:510px;width:500px;height:60px"></pre>
            <x-private id=late style="display:block;position:absolute;left:20px;top:650px;width:500px;height:40px"></x-private>
            <script>
            document.querySelector('#input').addEventListener('input', e => document.querySelector('h1').textContent = 'Reflected ' + e.target.value);
            if(location.pathname==='/security') {
              const closed=document.querySelector('#closed').attachShadow({mode:'closed'});
              closed.innerHTML='<span>Private component</span>';
              document.querySelector('#input').addEventListener('input', e => {
                const encoded=btoa(unescape(encodeURIComponent(e.target.value)));
                closed.querySelector('span').textContent=e.target.value;
                document.querySelector('h1').textContent=encoded;
                document.querySelector('#folded').textContent=encoded.match(/.{1,37}/g).join('\\n');
                document.querySelector('#late').attachShadow({mode:'closed'}).textContent=e.target.value;
                history.replaceState(null,'','/session/'+encodeURIComponent(encoded)+'?token='+encodeURIComponent(e.target.value));
              });
            }
            let count=0; document.querySelector('#before').onclick=()=>document.querySelector('#clock').textContent=String(++count);
            if(location.pathname==='/wait')setTimeout(()=>{const e=document.createElement('div');e.id='ready';e.textContent='Ready';document.body.append(e)},4000);
            if(location.pathname.startsWith('/once/'))document.querySelector('h1').textContent=document.cookie.includes('fixture=present')?'Cookie kept':'Cookie missing';
            if(location.pathname==='/tampered-selection'){
              const key=Object.keys(window).find(value=>value.startsWith('__alertifyInspection_'));
              window[key].select=()=>({error:document.querySelector('#input').value});
            }
            </script></body></html>
            """;

    @BeforeAll
    static void startFixture() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/authenticate") || path.equals("/logout")) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                boolean valid = body.contains("username=" + LOGIN_USER) && body.contains("password=" + LOGIN_PASSWORD);
                exchange.getResponseHeaders().add("Set-Cookie", path.equals("/authenticate") && valid ? "session=fixture; Path=/; HttpOnly; SameSite=Lax" : "session=; Path=/; Max-Age=0");
                exchange.getResponseHeaders().set("Location", path.equals("/authenticate") && valid ? "/account" : "/login?loggedOut=true");
                exchange.sendResponseHeaders(303, -1);
                exchange.close();
                return;
            }
            if (path.equals("/login") || path.equals("/account")) {
                boolean authenticated = path.equals("/account") && exchange.getRequestHeaders().getOrDefault("Cookie", List.of()).stream().anyMatch(cookie -> cookie.contains("session=fixture"));
                String page = authenticated ? "<main id=authenticated>Área autenticada<a id=logout href='/logout'>Cerrar sesión</a></main>"
                        : "<main>Sesión cerrada<form method=post action='/authenticate'><label>Usuario<input id=username name=username></label><label>Contraseña<input id=password name=password type=password></label><button id=login>Ingresar</button></form></main>";
                byte[] bytes = ("<!doctype html><html><body>" + page + "</body></html>").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
                return;
            }
            if (path.equals("/redirect")) {
                exchange.getResponseHeaders().set("Location", "http://localhost:" + server.getAddress().getPort() + "/html");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            int count = REQUESTS.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
            boolean once = path.startsWith("/once/");
            exchange.getResponseHeaders().set("Content-Type", path.equals("/json") ? "application/json" : "text/html; charset=utf-8");
            if (once) exchange.getResponseHeaders().add("Set-Cookie", "fixture=present; Path=/; SameSite=Lax");

            byte[] bytes = HTML.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(once && count > 1 ? 410 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopFixture() { if (server != null) server.stop(0); }

    @ParameterizedTest
    @ValueSource(strings = {"chromium", "firefox", "webkit"})
    void protectsUnknownPasswordValuesWithoutChangingTheField(String engine) throws Exception {
        try (var harness = new Harness()) {
            harness.success("OPEN", settings(engine, "/html", ""));
            var observation = harness.success("OBSERVE", Map.of());
            assertThat(JSON.writeValueAsString(observation).contains("fixture-canary")).as("A page-provided password must not leave in ARIA or control metadata").isFalse();
            assertThat(observation.path("snapshot").asString()).contains("[REDACTED]", "Public content");
            harness.success("EXECUTE", settings(engine, "/html", "QUERY #password\nCHECK ATTRIBUTE value fixture-canary"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"chromium", "firefox", "webkit"})
    void masksClosedComponentsAndBase64AndPublishesOnlyFilteredCurrentUrls(String engine) throws Exception {
        try (var harness = new Harness()) {
            harness.success("OPEN", settings(engine, "/security", ""));
            String value = "canary-private-" + UUID.randomUUID() + "-credential-".repeat(8);
            String encoded = java.util.Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
            var payload = settings(engine, "/security", "QUERY #input\nFILL configs.Canary");
            payload.set("values", JSON.valueToTree(List.of(Map.of("source", "CONFIGURATION", "name", "Canary", "value", value))));
            harness.success("EXECUTE", payload);
            var observation = harness.success("OBSERVE", Map.of());
            assertThat(JSON.writeValueAsString(observation).contains(value)).isFalse();
            assertThat(JSON.writeValueAsString(observation).contains(encoded)).isFalse();
            var capture = harness.request("SCREENSHOT", Map.of());
            assertThat(capture.getType()).isEqualTo("IMAGE");
            var image = ImageIO.read(new ByteArrayInputStream(capture.getImage().toByteArray()));
            for (int y : List.of(260, 460, 530, 660)) {
                int color = image.getRGB(30, y);
                assertThat((color >> 16) & 255).as("Mask red channel at y=%s", y).isGreaterThan(220);
                assertThat((color >> 8) & 255).as("Mask green channel at y=%s", y).isLessThan(60);
                assertThat(color & 255).as("Mask blue channel at y=%s", y).isGreaterThan(220);
            }
            String currentUrl = harness.events.stream().map(Event::event).filter(event -> event.getType().equals("PAGE_STATE")).reduce((first, second) -> second).map(event -> JSON.readTree(event.getJson()).path("currentUrl").asString()).orElseThrow();
            assertThat(currentUrl).contains("/session/[REDACTED]", "token=[REDACTED]");
            assertThat(currentUrl.contains(value) || currentUrl.contains(encoded)).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"chromium", "firefox", "webkit"})
    void displaysRedirectedOriginAndKeepsFillAvailable(String engine) throws Exception {
        try (var harness = new Harness()) {
            harness.success("OPEN", settings(engine, "/redirect", ""));
            var payload = settings(engine, "/redirect", "QUERY #input\nFILL secrets.Canary");
            payload.set("values", JSON.valueToTree(List.of(Map.of("source", "SECRET", "name", "Canary", "value", "redirect-fixture-value"))));
            harness.success("EXECUTE", payload);
            String currentUrl = harness.events.stream().map(Event::event).filter(event -> event.getType().equals("PAGE_STATE")).reduce((first, second) -> second).map(event -> JSON.readTree(event.getJson()).path("currentUrl").asString()).orElseThrow();
            assertThat(currentUrl).startsWith("http://localhost:").endsWith("/html");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"chromium", "firefox", "webkit"})
    void opensSingleUseHtmlWithOneNavigationAndKeepsItsCookies(String engine) throws Exception {
        String path = "/once/" + UUID.randomUUID();
        try (var harness = new Harness()) {
            harness.success("OPEN", settings(engine, path, ""));
            var observed = harness.success("OBSERVE", Map.of());
            assertThat(observed.path("snapshot").asString().contains("Cookie kept")).isTrue();
            assertThat(REQUESTS.get(path).get()).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"chromium", "firefox", "webkit"})
    void redactsReflectedEscapedValuesInTheActualObservationReply(String engine) throws Exception {
        try (var harness = new Harness()) {
            harness.success("OPEN", settings(engine, "/html", ""));
            String prefix = "canary-" + UUID.randomUUID();
            String value = prefix + "\"quote\\slash\nline";
            var payload = settings(engine, "/html", "QUERY #input\nFILL configs.Canary");
            payload.set("values", JSON.valueToTree(List.of(Map.of("source", "CONFIGURATION", "name", "Canary", "value", value))));
            harness.success("EXECUTE", payload);
            var observed = harness.success("OBSERVE", Map.of());
            String serialized = JSON.writeValueAsString(observed);
            assertThat(serialized.contains(prefix)).as("Protected canary must not leave the browser").isFalse();
            assertThat(observed.path("snapshot").asString().contains("[REDACTED]")).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"chromium", "firefox", "webkit"})
    void streamsMaskedFramesWhileWaitingForACondition(String engine) throws Exception {
        try (var harness = new Harness()) {
            harness.success("OPEN", settings(engine, "/wait", ""));
            harness.events.clear();
            harness.success("EXECUTE", settings(engine, "/wait", "WAIT_VISIBLE #ready 6s"));
            var frames = harness.events.stream().filter(event -> event.event().getType().equals("FRAME")).toList();
            assertThat(frames.size()).isGreaterThanOrEqualTo(5);
            long gap = 0;
            for (int index = 1; index < frames.size(); index++)
                gap = Math.max(gap, (frames.get(index).at() - frames.get(index - 1).at()) / 1_000_000);

            assertThat(gap).isLessThan(1500);
            for (var event : frames) {
                var image = ImageIO.read(new ByteArrayInputStream(event.event().getImage().toByteArray()));
                int color = image.getRGB(30, 30);
                assertThat((color >> 16) & 255).isGreaterThan(220);
                assertThat((color >> 8) & 255).isLessThan(60);
                assertThat(color & 255).isGreaterThan(220);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"chromium", "firefox", "webkit"})
    void bindsSelectionsToTheCapturedTargetAndRejectsTheBackground(String engine) {
        try (var session = PlaywrightBrowserSession.open(BrowserEngine.valueOf(engine.toUpperCase(java.util.Locale.ROOT)))) {
            var page = session.page();
            var inspection = new BrowserPageInspection(page, () -> { });
            session.navigate(origin + "/html", 10_000);
            inspection.beginFrame(1);
            inspection.finishFrame(1);
            assertThat(inspection.select(1, 40, 120)).containsEntry("selector", "#before");
            page.evaluate("document.querySelector('#clock').textContent='Changed elsewhere'");
            assertThat(inspection.select(1, 40, 120)).containsEntry("selector", "#before");
            page.evaluate("document.querySelector('#before').id='after'");
            assertThat(inspection.select(1, 40, 120)).containsEntry("error", "STALE_SELECTION");
            inspection.beginFrame(2);
            inspection.finishFrame(2);
            assertThat(inspection.select(2, 40, 120)).containsEntry("selector", "#after");
            page.evaluate("const e=document.querySelector('#after');e.replaceWith(e.cloneNode(true))");
            assertThat(inspection.select(2, 40, 120)).containsEntry("error", "STALE_SELECTION");
            inspection.beginFrame(3);
            inspection.finishFrame(3);
            assertThat(inspection.select(3, 1000, 800)).containsEntry("error", "NO_SELECTION");
            page.evaluate("const e=document.createElement('div');e.id='overlay';e.style='position:absolute;left:20px;top:100px;width:180px;height:40px;z-index:10';document.body.append(e)");
            inspection.beginFrame(4);
            inspection.finishFrame(4);
            page.evaluate("document.querySelector('#overlay').remove()");
            assertThat(inspection.select(4, 40, 120)).containsEntry("error", "STALE_SELECTION");
            page.evaluate("document.querySelector('#after').insertAdjacentHTML('afterend','<button id=after>Duplicate id</button>')");
            String selector = BrowserPageInspection.selector(page.locator("#after").first());
            assertThat(page.locator(selector).count()).isEqualTo(1);
            assertThat(BrowserPageInspection.selector(page.locator("body"))).isNull();
        }
    }

    @Test
    void reportsTheActualTestEngineAndContinuesWithoutRepeatingTheClick() throws Exception {
        try (var harness = new Harness()) {
            var settings = settings("chromium", "/html", "QUERY #before\nUSE");
            settings.set("browsers", JSON.valueToTree(List.of("chromium", "firefox")));
            settings.set("values", JSON.valueToTree(List.of(Map.of("source", "CONFIGURATION", "name", "Canary", "value", "firefox"))));
            harness.success("OPEN", settings);
            var report = harness.success("TEST", settings);
            assertThat(report.path("passed").asBoolean()).isTrue();
            assertThat(report.path("activeEngine").asString()).isEqualTo("firefox");
            assertThat(report.path("browsers").get(1).path("browser").asString()).isEqualTo("firefox");
            settings.put("steps", "QUERY #before\nUSE\nQUERY #clock\nCHECK TEXT 1");
            harness.success("EXECUTE", settings);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"chromium", "firefox", "webkit"})
    void generatesConciseSelectorsForSemanticControlsAndRepeatedMenus(String engine) {
        try (var session = PlaywrightBrowserSession.open(BrowserEngine.valueOf(engine.toUpperCase(java.util.Locale.ROOT)))) {
            var page = session.page();
            page.setContent("<header><div><nav><ul><li><button>Institucional</button></li></ul></nav></div></header><footer><button>Contacto</button></footer>");
            String selector = BrowserPageInspection.selector(page.locator("header button"));
            assertThat(selector).isEqualTo("button:text-is(\"Institucional\")");
            page.locator(selector).hover();
            // Extra wrappers and a different sibling order must not change the selected control.
            page.evaluate("const button=document.querySelector('header button');const wrapper=document.createElement('div');button.replaceWith(wrapper);wrapper.append(button);wrapper.insertAdjacentHTML('afterbegin','<button>Otro</button>')");
            assertThat(page.locator(selector).count()).isEqualTo(1);
            assertThat(page.locator(selector).innerText()).isEqualTo("Institucional");

            page.setContent("<nav id=primary><div><button>Institucional</button></div></nav><nav id=secondary><button>Institucional</button></nav>");
            selector = BrowserPageInspection.selector(page.locator("#primary button"));
            assertThat(selector).isEqualTo("#primary button:text-is(\"Institucional\")");
            assertThat(page.locator(selector).count()).isEqualTo(1);
            page.locator(selector).hover();

            page.setContent("<button>Institucional</button><button style='display:none'>Institucional</button>");
            selector = BrowserPageInspection.selector(page.locator("button").first());
            assertThat(selector).isEqualTo("button:text-is(\"Institucional\")");
            assertThat(page.locator(selector).filter(new com.microsoft.playwright.Locator.FilterOptions().setVisible(true)).count()).isEqualTo(1);

            page.setContent("<button><span>Institucional</span><span> ANEP</span></button><button>Contacto</button>");
            selector = BrowserPageInspection.selector(page.locator("button").first());
            assertThat(selector).isEqualTo("button:has-text(\"Institucional ANEP\")");
            page.locator(selector).hover();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"chromium", "firefox", "webkit"})
    void verifiesSelectorIdentityAndEscapesAttributesAndTextWithoutReadingFieldValues(String engine) {
        try (var session = PlaywrightBrowserSession.open(BrowserEngine.valueOf(engine.toUpperCase(java.util.Locale.ROOT)))) {
            var page = session.page();
            page.setContent("<button id=duplicate data-testid=duplicate>Primero</button><button id=duplicate data-testid=duplicate>Segundo</button>");
            assertThat(BrowserPageInspection.selector(page.locator("button").nth(1))).isEqualTo("button:text-is(\"Segundo\")");

            page.setContent("<button>Otro</button><button>Institucional &quot;ANEP&quot; \\ Uruguay</button>");
            String selector = BrowserPageInspection.selector(page.locator("button").nth(1));
            assertThat(selector).contains(":text-is(");
            assertThat(page.locator(selector).count()).isEqualTo(1);
            assertThat(page.locator(selector).innerText()).isEqualTo("Institucional \"ANEP\" \\ Uruguay");

            page.setContent("<input name='user&quot;name' value='private-field-canary'><input type=password value='private-password-canary'><textarea>private-textarea-canary</textarea>");
            selector = BrowserPageInspection.selector(page.locator("input").first());
            assertThat(selector).isEqualTo("input[name=\"user\\\"name\"]");
            assertThat(page.locator(selector).count()).isEqualTo(1);
            assertThat(BrowserPageInspection.selector(page.locator("input[type=password]"))).doesNotContain("private-", ":text", ":has-text", "value=");
            assertThat(BrowserPageInspection.selector(page.locator("textarea"))).doesNotContain("private-", ":text", ":has-text");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"chromium", "firefox", "webkit"})
    void shortensStructuralFallbackAndUsesSemanticSelectorsForCapturedFrames(String engine) {
        try (var session = PlaywrightBrowserSession.open(BrowserEngine.valueOf(engine.toUpperCase(java.util.Locale.ROOT)))) {
            var page = session.page();
            var inspection = new BrowserPageInspection(page, () -> { });
            session.navigate(origin + "/html", 10_000);
            page.setContent("<main><div><button style='width:100px;height:40px'></button></div><div><button style='width:100px;height:40px'></button></div></main>");
            String selector = BrowserPageInspection.selector(page.locator("button").first());
            assertThat(selector).doesNotStartWith("body").contains(":nth-of-type(");
            assertThat(page.locator(selector).count()).isEqualTo(1);

            page.setContent("<header><nav><button style='position:absolute;left:20px;top:100px;width:180px;height:40px'>Institucional</button></nav></header>");
            inspection.beginFrame(1);
            inspection.finishFrame(1);
            assertThat(inspection.select(1, 40, 120)).containsEntry("selector", "button:text-is(\"Institucional\")");
            page.locator("button").evaluate("element => element.textContent='Contacto'");
            assertThat(inspection.select(1, 40, 120)).containsEntry("error", "STALE_SELECTION");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"chromium", "firefox", "webkit"})
    void keepsCapturingDuringAProcedureReplyAndProtectsItsReturnedValue(String engine) throws Exception {
        try (var harness = new Harness()) {
            harness.success("OPEN", settings(engine, "/html", ""));
            harness.procedureResult = "canary-" + UUID.randomUUID() + "\"quoted";
            var payload = settings(engine, "/html", "CALL PROCEDURE \"Fixture\" POINTER \"/code\" AS code\nQUERY #input\nFILL vars.code");
            payload.set("values", JSON.valueToTree(List.of(Map.of("source", "PROCEDURE", "name", PlaywrightDslValues.key("Fixture", "/code"), "value", "pv.fixture-capability"))));
            harness.events.clear();
            harness.success("EXECUTE", payload);
            assertThat(harness.events.stream().filter(event -> event.event().getType().equals("FRAME")).count()).isGreaterThanOrEqualTo(5);
            var observation = harness.success("OBSERVE", Map.of());
            assertThat(JSON.writeValueAsString(observation).contains("canary-")).isFalse();
        }
    }

    @Test
    void replaysProtectedLoginAndLogoutInWizardAndAlertAcrossAllEngines() throws Exception {
        var values = List.of(
                new app.alertify.alerts.AlertExecutionValue(app.alertify.alerts.AlertExecutionValueSource.SECRET, PlaywrightDslValues.key("Fixture", "/username"), LOGIN_USER),
                new app.alertify.alerts.AlertExecutionValue(app.alertify.alerts.AlertExecutionValueSource.SECRET, PlaywrightDslValues.key("Fixture", "/password"), LOGIN_PASSWORD));
        try (var harness = new Harness()) {
            harness.success("OPEN", settings("chromium", "/login", ""));
            var payload = settings("chromium", "/login", LOGIN_STEPS);
            payload.set("browsers", JSON.valueToTree(List.of("chromium", "firefox", "webkit")));
            payload.set("values", JSON.valueToTree(values.stream().map(value -> Map.of("source", value.source().name(), "name", value.name(), "value", value.value())).toList()));
            var result = harness.success("TEST", payload);
            assertThat(result.path("passed").asBoolean()).as("Clean login/logout replay in all three engines").isTrue();
            assertThat(harness.success("OBSERVE", Map.of()).toString().contains(LOGIN_PASSWORD)).isFalse();
        }
        var context = new app.alertify.alerts.AlertExecutionContext("", Map.of(), values);
        var result = new PlaywrightPageEngine(origin + "/login", true, true, true, 10, 5, LOGIN_STEPS).evaluate(context);
        assertThat(result.status().name()).isEqualTo("SUCCESS");
        assertThat(result.statusMessage().get("endpoint")).isEqualTo(origin);
        assertThat(result.statusMessage().toString().contains(LOGIN_PASSWORD)).isFalse();
    }

    @Test
    void rejectsNonHtmlWithoutReportingAnOpenBrowser() throws Exception {
        try (var harness = new Harness()) {
            var error = harness.request("OPEN", settings("chromium", "/json", ""));
            assertThat(error.getType()).isEqualTo("ERROR");
            assertThat(JSON.readTree(error.getJson()).path("error").asString()).contains("HTML");
            assertThat(harness.request("OBSERVE", Map.of()).getType()).isEqualTo("ERROR");
        }
    }

    @Test
    void rejectsUntrustedSelectionCodesWithoutLeakingProtectedValues() throws Exception {
        try (var harness = new Harness()) {
            harness.success("OPEN", settings("chromium", "/tampered-selection", ""));
            String canary = "canary-" + UUID.randomUUID();
            var payload = settings("chromium", "/tampered-selection", "QUERY #input\nFILL configs.Canary");
            payload.set("values", JSON.valueToTree(List.of(Map.of("source", "CONFIGURATION", "name", "Canary", "value", canary))));
            harness.success("EXECUTE", payload);
            long frame = harness.events.stream().map(Event::event).filter(event -> event.getType().equals("FRAME")).reduce((first, second) -> second).orElseThrow().getFrameId();
            var reply = harness.request("SELECT", Map.of("frameId", frame, "x", 40, "y", 120));
            assertThat(reply.getType()).isEqualTo("ERROR");
            assertThat(reply.getJson().contains(canary)).as("A page-controlled error code must never expose a protected value").isFalse();
            assertThat(JSON.readTree(reply.getJson()).path("failureCode").asString()).isEqualTo("STALE_SELECTION");
        }
    }

    private static ObjectNode settings(String engine, String path, String steps) {
        return (ObjectNode) JSON.valueToTree(Map.of("url", origin + path, "engine", engine, "browsers", List.of(engine), "steps", steps, "loadTimeoutSeconds", 10, "elementTimeoutSeconds", 5));
    }

    private record Event(WizardEvent event, long at) { }

    private static final class Harness implements AutoCloseable {
        private final List<Event> events = new ArrayList<>();
        private final InteractiveBrowserMain child = new InteractiveBrowserMain(this::event);
        private String procedureResult;

        private void event(WizardEvent event) {
            events.add(new Event(event, System.nanoTime()));
            if (event.getType().equals("PROCEDURE_CALL")) {
                CompletableFuture.delayedExecutor(3, TimeUnit.SECONDS).execute(() -> child.procedureReply(WizardRequest.newBuilder()
                        .setOperation("PROCEDURE_REPLY").setRequestId(event.getRequestId()).setJson(JSON.writeValueAsString(Map.of("value", procedureResult))).build()));
            }
        }

        private WizardEvent request(String operation, Object payload) throws Exception {
            String id = UUID.randomUUID().toString();
            child.handle(WizardRequest.newBuilder().setOperation(operation).setRequestId(id).setJson(JSON.writeValueAsString(payload)).build());
            return events.stream().map(Event::event).filter(event -> event.getRequestId().equals(id)).reduce((first, second) -> second).orElseThrow();
        }

        private JsonNode success(String operation, Object payload) throws Exception {
            var reply = request(operation, payload);
            assertThat(reply.getType()).as("Operation %s", operation).isEqualTo("RESULT");
            return JSON.readTree(reply.getJson());
        }

        @Override public void close() { child.close(); }
    }
}
