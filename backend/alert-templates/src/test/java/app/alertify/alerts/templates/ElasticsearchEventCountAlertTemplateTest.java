package app.alertify.alerts.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import app.alertify.alerts.AlertExecutionContext;
import app.alertify.alerts.execution.AlertExecutionStatus;
import app.alertify.alerts.template.annotation.AlertParameter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ElasticsearchEventCountAlertTemplateTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-01T12:00:00Z"), ZoneOffset.UTC);

    @ParameterizedTest
    @CsvSource({
        "warn_if_bigger,20,21,WARN", "warn_if_bigger,20,20,SUCCESS",
        "warn_if_lower,5,4,WARN", "warn_if_lower,5,5,SUCCESS",
        "warn_if_equal,0,0,WARN", "warn_if_equal,0,1,SUCCESS",
        "warn_if_distinct,1,0,WARN", "warn_if_distinct,1,1,SUCCESS"
    })
    void comparesExactCountsIncludingAbsence(String comparison, long threshold, long count, AlertExecutionStatus status) throws Exception {
        HttpServer server = server(exchange -> respond(exchange, 200, response(count)));
        try {
            var result = template(url(server), null, "{\"match_all\":{}}", comparison, threshold, 3).evaluate(new AlertExecutionContext());
            assertEquals(status, result.status());
            assertEquals(count, result.statusMessage().get("count"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsDslWithAnAbsoluteHalfOpenWindowAndOptionalHeadersThroughABasePath() throws Exception {
        AtomicReference<JsonNode> body = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> header = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals("application/json", exchange.getRequestHeaders().getFirst("Content-Type"));
            body.set(JSON.readTree(exchange.getRequestBody().readAllBytes()));
            path.set(exchange.getRequestURI().toString());
            header.set(exchange.getRequestHeaders().getFirst("X-Fixture"));
            respond(exchange, 200, response(3));
        });
        try {
            var result = template(url(server) + "/proxy/", "[\"X-Fixture: private-fixture\"]", "{\"term\":{\"service.name\":\"private-service\"}}", "warn_if_equal", 0, 3)
                .evaluate(new AlertExecutionContext());
            assertEquals("/proxy/logs-*,events/_count?allow_no_indices=false&ignore_unavailable=false", path.get());
            assertEquals("private-fixture", header.get());
            assertEquals("private-service", body.get().at("/query/bool/filter/0/term/service.name").asText());
            assertEquals("2026-11-01T11:54:00Z", body.get().at("/query/bool/filter/1/range/@timestamp/gte").asText());
            assertEquals("2026-11-01T11:59:00Z", body.get().at("/query/bool/filter/1/range/@timestamp/lt").asText());
            assertFalse(result.statusMessage().toString().contains("private"));
            assertEquals("2026-11-01T11:54:00Z", result.statusMessage().get("windowFrom"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void usesACustomTimeFieldWithoutDelayAndSendsNoAuthorizationByDefault() throws Exception {
        HttpServer server = server(exchange -> {
            assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
            JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
            assertEquals("2026-11-01T11:55:00Z", body.at("/query/bool/filter/1/range/event.created/gte").asText());
            assertEquals("2026-11-01T12:00:00Z", body.at("/query/bool/filter/1/range/event.created/lt").asText());
            respond(exchange, 200, response(0));
        });
        try {
            var result = new ElasticsearchEventCountAlertTemplate(url(server), " ", "events", "{\"match_all\":{}}", "event.created", 5, "warn_if_equal", 0, 0, 3, CLOCK)
                .evaluate(new AlertExecutionContext());
            assertEquals(AlertExecutionStatus.WARN, result.status());
            assertEquals(0L, result.statusMessage().get("count"));
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @CsvSource({"400,query_failure", "401,auth_failed", "403,elasticsearch_access_denied", "404,elasticsearch_index_unavailable", "500,elasticsearch_http_failure", "302,elasticsearch_http_failure"})
    void reportsHttpFailuresWithoutTreatingThemAsZeroOrLeakingTheBody(int status, String reason) throws Exception {
        HttpServer server = server(exchange -> {
            exchange.getResponseHeaders().set("Location", "/do-not-follow");
            respond(exchange, status, "private-response-body");
        });
        try {
            var context = new AlertExecutionContext();
            var result = template(url(server), null, "{\"match_all\":{}}", "warn_if_equal", 0, 3).evaluate(context);
            assertEquals(AlertExecutionStatus.WARN, result.status());
            assertEquals(reason, result.statusMessage().get("failureReason"));
            assertFalse(result.statusMessage().containsKey("count"));
            assertFalse(result.statusMessage().toString().contains("private-response-body"));
        } finally {
            server.stop(0);
        }
    }

    static Stream<Arguments> invalidResponses() {
        return Stream.of(
            Arguments.of("not-json", "elasticsearch_invalid_response"),
            Arguments.of("null", "elasticsearch_invalid_response"),
            Arguments.of("{}", "elasticsearch_invalid_response"),
            Arguments.of(response(-1), "elasticsearch_invalid_response"),
            Arguments.of(response(0).replace("\"count\":0", "\"count\":0.5"), "elasticsearch_invalid_response"),
            Arguments.of(response(0).replace("\"count\":0", "\"count\":9223372036854775808"), "elasticsearch_invalid_response"),
            Arguments.of("{\"count\":0,\"_shards\":{\"total\":1,\"failed\":0}}", "elasticsearch_invalid_response"),
            Arguments.of("{\"count\":0,\"_shards\":{\"total\":2,\"successful\":1,\"failed\":1}}", "elasticsearch_partial_result"),
            Arguments.of("{\"count\":0,\"_shards\":{\"total\":0,\"successful\":0,\"failed\":0}}", "elasticsearch_partial_result"),
            Arguments.of("{\"count\":0,\"_shards\":{\"total\":2,\"successful\":1,\"failed\":0}}", "elasticsearch_partial_result"),
            Arguments.of(response(0).replace("{\"count\"", "{\"timed_out\":true,\"count\""), "elasticsearch_partial_result"),
            Arguments.of(response(0).replace("{\"count\"", "{\"terminated_early\":true,\"count\""), "elasticsearch_partial_result")
        );
    }

    @ParameterizedTest
    @MethodSource("invalidResponses")
    void rejectsInvalidOrPartialCounts(String body, String reason) throws Exception {
        HttpServer server = server(exchange -> respond(exchange, 200, body));
        try {
            var result = template(url(server), null, "{\"match_all\":{}}", "warn_if_bigger", 20, 3).evaluate(new AlertExecutionContext());
            assertEquals(AlertExecutionStatus.WARN, result.status());
            assertEquals(reason, result.statusMessage().get("failureReason"));
            assertFalse(result.statusMessage().containsKey("count"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void timesOutWhileReadingTheResponseBody() throws Exception {
        HttpServer server = server(exchange -> {
            exchange.sendResponseHeaders(200, 100);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            try {
                Thread.sleep(1500);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            var result = template(url(server), null, "{\"match_all\":{}}", "warn_if_equal", 0, 1).evaluate(new AlertExecutionContext());
            assertEquals("timeout", result.statusMessage().get("failureReason"));
            assertFalse(result.statusMessage().containsKey("count"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsOversizedResponsesWithoutPersistingRemoteContent() throws Exception {
        HttpServer server = server(exchange -> respond(exchange, 200, "private-remote-content".repeat(60_000)));
        try {
            var result = template(url(server), null, "{\"match_all\":{}}", "warn_if_equal", 0, 3).evaluate(new AlertExecutionContext());
            assertEquals(AlertExecutionStatus.WARN, result.status());
            assertTrue(result.statusMessage().containsKey("failureReason"));
            assertFalse(result.statusMessage().containsKey("count"));
            assertFalse(result.statusMessage().toString().contains("private-remote-content"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reportsConnectionFailureWithoutProducingACount() throws Exception {
        HttpServer server = server(exchange -> respond(exchange, 200, response(0)));
        String url = url(server);
        server.stop(0);
        var result = template(url, null, "{\"match_all\":{}}", "warn_if_equal", 0, 3).evaluate(new AlertExecutionContext());
        assertEquals(AlertExecutionStatus.WARN, result.status());
        assertEquals("connection_failure", result.statusMessage().get("failureReason"));
        assertFalse(result.statusMessage().containsKey("count"));
    }

    @ParameterizedTest
    @CsvSource({"0,0,0,10", "-1,0,0,10", "5,-1,0,10", "5,0,-1,10", "5,0,0,0", "5,0,0,3601"})
    void rejectsInvalidNumericConfiguration(int window, int delay, long threshold, int timeout) {
        assertThrows(IllegalArgumentException.class, () -> new ElasticsearchEventCountAlertTemplate(
            "https://example.test", null, "events", "{\"match_all\":{}}", "@timestamp", window, "warn_if_equal", threshold, delay, timeout
        ));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../events", "logs?private=value", "logs#private", "logs%2Fprivate", "logs,,events", "logs,", "logs events"})
    void rejectsIndexTargetsThatCouldAlterTheRequest(String indices) {
        assertThrows(IllegalArgumentException.class, () -> new ElasticsearchEventCountAlertTemplate(
            "https://example.test", null, indices, "{\"match_all\":{}}", "@timestamp", 5, "warn_if_equal", 0, 0, 10
        ));
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad-json-private-value", "[]", "{}", "{\"query\":{\"match_all\":{}}}", "{\"match_all\":{},\"size\":0}"})
    void rejectsMalformedFiltersWithoutEchoingTheirContents(String query) {
        var template = template("http://127.0.0.1:1", null, query, "warn_if_equal", 0, 3);
        var exception = assertThrows(IllegalArgumentException.class, () -> template.evaluate(new AlertExecutionContext()));
        assertNull(exception.getCause());
        assertFalse(exception.getMessage().contains("private-value"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"private-value", "{}", "[1]", "[\"Invalid-private-value\"]", "[\"X-Fixture: private\\r\\nInjection\"]", "[\"Host: private-value\"]"})
    void sanitizesInvalidHeaderErrors(String headers) {
        var template = template("http://127.0.0.1:1", headers, "{\"match_all\":{}}", "warn_if_equal", 0, 3);
        var exception = assertThrows(IllegalArgumentException.class, () -> template.evaluate(new AlertExecutionContext()));
        assertNull(exception.getCause());
        assertFalse(exception.getMessage().contains("private"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://user:private@example.test", "http://example.test?private=value", "http://example.test#private", "file:///tmp/private", "not-a-url-private"})
    void rejectsUnsafeBaseUrlsWithoutEchoingThem(String url) {
        var exception = assertThrows(IllegalArgumentException.class, () -> template(url, null, "{\"match_all\":{}}", "warn_if_equal", 0, 3));
        assertNull(exception.getCause());
        assertFalse(exception.getMessage().contains("private"));
    }

    @Test
    void declaresTheAgreedParameters() throws Exception {
        var type = ElasticsearchEventCountAlertTemplate.class;
        assertEquals("@timestamp", type.getDeclaredField("timeField").getAnnotation(AlertParameter.class).defaultValue());
        assertFalse(type.getDeclaredField("headersJson").getAnnotation(AlertParameter.class).required());
        assertEquals(10, Stream.of(type.getDeclaredFields()).filter(field -> field.isAnnotationPresent(AlertParameter.class)).count());
        assertThrows(NoSuchFieldException.class, () -> type.getDeclaredField("description"));
    }

    private static ElasticsearchEventCountAlertTemplate template(String url, String headers, String query, String comparison, long threshold, int timeout) {
        return new ElasticsearchEventCountAlertTemplate(url, headers, "logs-*,events", query, "@timestamp", 5, comparison, threshold, 60, timeout, CLOCK);
    }

    private static String response(long count) {
        return "{\"count\":" + count + ",\"_shards\":{\"total\":1,\"successful\":1,\"skipped\":0,\"failed\":0}}";
    }

    private static String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static HttpServer server(Handler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                handler.handle(exchange);
            }
        });
        server.start();
        return server;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
