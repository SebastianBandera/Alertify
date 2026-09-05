package app.alertify.alerts.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import app.alertify.alerts.AlertExecutionContext;
import app.alertify.alerts.AlertResult;
import app.alertify.alerts.execution.AlertExecutionStatus;
import app.alertify.alerts.template.annotation.AlertParameter;
import app.alertify.alerts.template.annotation.AlertTemplate;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

class WebRequestAlertTemplateTest {

    @Test
    void declaresMultilineFieldsForTheBodyHeadersAndRegexes() throws ReflectiveOperationException {
        AlertTemplate template = WebRequestAlertTemplate.class.getAnnotation(AlertTemplate.class);

        assertEquals("alerts.template.webRequest.name", template.nameKey());
        assertTrue(parameter("body").multiline());
        assertTrue(parameter("headersJson").multiline());
        assertTrue(parameter("responseBodyRegexes").multiline());
        assertEquals("GET", parameter("method").defaultValue());
        assertEquals("200", parameter("expectedStatusCodes").defaultValue());
    }

    @Test
    void sendsTheConfiguredRequestAndValidatesAllRegexes() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> requestHeader = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requestHeader.set(exchange.getRequestHeaders().getFirst("X-Alertify-Test"));
            respond(exchange, 201, "{\n  \"status\": \"UP\",\n  \"version\": 1\n}");
        });
        try {
            AlertResult result = template(
                server,
                "POST",
                "200,201",
                "{\"sample\":true}",
                "[\"X-Alertify-Test: enabled\", \"Content-Type: application/json\"]",
                "\"status\"\\s*:\\s*\"UP\"\n\"version\"\\s*:\\s*1"
            ).evaluate(new AlertExecutionContext());

            assertEquals(AlertExecutionStatus.SUCCESS, result.status());
            assertEquals(201, result.statusMessage().get("statusCode"));
            assertEquals(2, result.statusMessage().get("regexCount"));
            assertEquals("{\"sample\":true}", requestBody.get());
            assertEquals("enabled", requestHeader.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reportsUnexpectedStatusAndRegexMismatchesAsWarnings() throws Exception {
        HttpServer server = server(exchange -> respond(exchange, 200, "{\"status\":\"DOWN\"}"));
        try {
            AlertResult statusResult = template(server, "GET", "204", null, "[]", null)
                .evaluate(new AlertExecutionContext());
            AlertResult regexResult = template(server, "GET", "200", null, "[]", "\"status\"\\s*:\\s*\"UP\"")
                .evaluate(new AlertExecutionContext());

            assertEquals(AlertExecutionStatus.WARN, statusResult.status());
            assertEquals("unexpectedStatusCode", statusResult.statusMessage().get("failureReason"));
            assertEquals(AlertExecutionStatus.WARN, regexResult.status());
            assertEquals("responseBodyRegexMismatch", regexResult.statusMessage().get("failureReason"));
            assertEquals(0, regexResult.statusMessage().get("unmatchedRegexIndex"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void treatsTimeoutsAsWarnings() throws Exception {
        HttpServer server = server(exchange -> {
            try {
                Thread.sleep(2_000);
                respond(exchange, 200, "ok");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            AlertResult result = template(server, "GET", "200", null, "[]", null, 1)
                .evaluate(new AlertExecutionContext());

            assertEquals(AlertExecutionStatus.WARN, result.status());
            assertEquals("timeout", result.statusMessage().get("failureReason"));
            assertEquals(1L, result.statusMessage().get("timeoutSeconds"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void treatsOversizedResponsesAsWarningsWithoutReadingTheirBody() throws Exception {
        HttpServer server = server(exchange -> {
            byte[] body = new byte[10 * 1024 * 1024 + 1];
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        });
        try {
            AlertResult result = template(server, "GET", "200", null, "[]", null)
                .evaluate(new AlertExecutionContext());

            assertEquals(AlertExecutionStatus.WARN, result.status());
            assertEquals("responseBodyTooLarge", result.statusMessage().get("failureReason"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsInvalidConfigurationBeforeSendingTheRequest() {
        WebRequestAlertTemplate invalidHeaders = new WebRequestAlertTemplate(
            "https://example.test", "GET", "200", null, "[1]", null, 3
        );
        WebRequestAlertTemplate invalidRegex = new WebRequestAlertTemplate(
            "https://example.test", "GET", "200", null, "[]", "[", 3
        );

        assertThrows(IllegalArgumentException.class, () -> invalidHeaders.evaluate(new AlertExecutionContext()));
        assertThrows(java.util.regex.PatternSyntaxException.class, () -> invalidRegex.evaluate(new AlertExecutionContext()));
    }

    private static WebRequestAlertTemplate template(
        HttpServer server,
        String method,
        String expectedStatusCodes,
        String body,
        String headersJson,
        String responseBodyRegexes
    ) {
        return template(server, method, expectedStatusCodes, body, headersJson, responseBodyRegexes, 3);
    }

    private static WebRequestAlertTemplate template(
        HttpServer server,
        String method,
        String expectedStatusCodes,
        String body,
        String headersJson,
        String responseBodyRegexes,
        int timeoutSeconds
    ) {
        return new WebRequestAlertTemplate(
            "http://127.0.0.1:" + server.getAddress().getPort() + "/",
            method,
            expectedStatusCodes,
            body,
            headersJson,
            responseBodyRegexes,
            timeoutSeconds
        );
    }

    private static HttpServer server(ExchangeHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                handler.handle(exchange);
            }
        });
        server.start();
        return server;
    }

    private static void respond(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static AlertParameter parameter(String name) throws ReflectiveOperationException {
        return WebRequestAlertTemplate.class.getDeclaredField(name).getAnnotation(AlertParameter.class);
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
