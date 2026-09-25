package app.alertify.alerts.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import app.alertify.alerts.AlertExecutionContext;
import app.alertify.alerts.AlertResult;
import app.alertify.alerts.execution.AlertExecutionStatus;
import app.alertify.alerts.template.annotation.AlertParameter;
import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.alerts.template.annotation.AlertTemplate;
import app.alertify.worker.contract.GitCredentials;
import app.alertify.worker.contract.GitProvider;

class GitLabPipelineAlertTemplateTest {

    private static final String TOKEN = "glpat-never-report-this";
    private static final Instant NOW = Instant.parse("2026-09-25T12:00:00Z");

    @Test
    void exposesTemplateMetadataAndSecretOnlyCredentials() throws Exception {
        AlertTemplate template = GitLabPipelineAlertTemplate.class.getAnnotation(AlertTemplate.class);
        AlertParameter credentials = parameter("credentials");

        assertEquals("alerts.template.gitLabPipeline.name", template.nameKey());
        assertEquals("app/alertify/alerts/templates/GitLabPipelineAlertTemplate.java", template.sourcePath());
        assertEquals(List.of(AlertParameterSource.SECRET), List.of(credentials.allowedSources()));
        assertEquals(List.of("GIT_SECRET"), List.of(credentials.allowedSecretValueTypes()));
        assertEquals("[\"main\"]", parameter("branchesJson").defaultValue());
        assertEquals("false", parameter("checkActiveDuration").defaultValue());
        assertEquals("60", parameter("maxActiveMinutes").defaultValue());
        assertEquals("false", parameter("checkFreshness").defaultValue());
        assertEquals("24", parameter("maxPipelineAgeHours").defaultValue());
        assertEquals("10", parameter("timeoutSeconds").defaultValue());
    }

    @Test
    void skipsPipelinesThatDidNotRunAndUsesThePreviousExecutedPipeline() throws Exception {
        HttpServer server = server(exchange -> {
            assertEquals("Bearer " + TOKEN, exchange.getRequestHeaders().getFirst("Authorization"));
            String path = path(exchange);
            if (path.startsWith("/api/v4/projects/7/pipelines?")) {
                respond(exchange, 200, "[" + pipeline(12, "skipped", "push", hoursAgo(1)) + "," + pipeline(11, "manual", "web", hoursAgo(2)) + "," + pipeline(10, "success", "push", hoursAgo(3)) + "]");
                return;
            }
            if (path.equals("/api/v4/projects/7/pipelines/10/trigger_jobs?per_page=100&page=1")) {
                respond(exchange, 200, "[]");
                return;
            }
            respond(exchange, 404, "{}");
        });
        try {
            AlertExecutionContext context = new AlertExecutionContext();
            AlertResult result = template(server, false, 60, false, 24).evaluate(context);

            assertEquals(AlertExecutionStatus.SUCCESS, result.status());
            Map<String, Object> branch = firstBranch(result);
            assertEquals(2, branch.get("ignoredPipelineCount"));
            assertEquals(10L, pipelineMessage(branch).get("id"));
            assertEquals("success", pipelineMessage(branch).get("status"));
            assertFalse(context.getState().contains(TOKEN));
            assertFalse(result.statusMessage().toString().contains(TOKEN));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void searchesSubsequentHistoryPagesForTheLatestExecutedPipeline() throws Exception {
        HttpServer server = server(exchange -> {
            String path = path(exchange);
            if (path.equals("/api/v4/projects/7/pipelines?ref=main&order_by=id&sort=desc&per_page=100&page=1")) {
                respond(exchange, 200, ignoredPipelines(100));
                return;
            }
            if (path.equals("/api/v4/projects/7/pipelines?ref=main&order_by=id&sort=desc&per_page=100&page=2")) {
                respond(exchange, 200, "[" + pipeline(10, "success", "push", hoursAgo(3)) + "]");
                return;
            }
            if (path.equals("/api/v4/projects/7/pipelines/10/trigger_jobs?per_page=100&page=1")) {
                respond(exchange, 200, "[]");
                return;
            }
            respond(exchange, 404, "{}");
        });
        try {
            AlertResult result = template(server, false, 60, false, 24).evaluate(new AlertExecutionContext());

            assertEquals(AlertExecutionStatus.SUCCESS, result.status());
            assertEquals(100, firstBranch(result).get("ignoredPipelineCount"));
            assertEquals(10L, pipelineMessage(firstBranch(result)).get("id"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void succeedsWithAnExplicitOutcomeWhenNoPipelineEverRan() throws Exception {
        HttpServer server = server(exchange -> {
            String path = path(exchange);
            if (path.startsWith("/api/v4/projects/7/pipelines?")) {
                respond(exchange, 200, "[" + pipeline(12, "scheduled", "schedule", hoursAgo(1)) + "," + pipeline(11, "skipped", "push", hoursAgo(2)) + "]");
                return;
            }
            if (path.equals("/api/v4/projects/7/repository/branches/main")) {
                respond(exchange, 200, "{\"name\":\"main\"}");
                return;
            }
            respond(exchange, 404, "{}");
        });
        try {
            AlertResult result = template(server, false, 60, true, 1).evaluate(new AlertExecutionContext());

            assertEquals(AlertExecutionStatus.SUCCESS, result.status());
            assertEquals("no_executed_pipeline", firstBranch(result).get("outcome"));
            assertEquals(false, firstBranch(result).get("warning"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void warnsWhenAConfiguredBranchDoesNotExist() throws Exception {
        HttpServer server = server(exchange -> {
            if (path(exchange).startsWith("/api/v4/projects/7/pipelines?")) {
                respond(exchange, 200, "[]");
                return;
            }
            respond(exchange, 404, "{}");
        });
        try {
            AlertResult result = template(server, false, 60, false, 24).evaluate(new AlertExecutionContext());

            assertEquals(AlertExecutionStatus.WARN, result.status());
            assertEquals("branch_not_found", firstBranch(result).get("failureReason"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void warnsWhenASameProjectChildPipelineFailedEvenIfTheParentSucceeded() throws Exception {
        HttpServer server = server(exchange -> {
            String path = path(exchange);
            if (path.startsWith("/api/v4/projects/7/pipelines?")) {
                respond(exchange, 200, "[" + pipeline(100, "success", "push", hoursAgo(1)) + "]");
                return;
            }
            if (path.equals("/api/v4/projects/7/pipelines/100/trigger_jobs?per_page=100&page=1")) {
                respond(exchange, 200, "[" + triggerJob(200, "failed") + "]");
                return;
            }
            if (path.equals("/api/v4/projects/7/pipelines/200")) {
                respond(exchange, 200, pipeline(200, "failed", "parent_pipeline", hoursAgo(1)));
                return;
            }
            if (path.equals("/api/v4/projects/7/pipelines/200/trigger_jobs?per_page=100&page=1")) {
                respond(exchange, 200, "[]");
                return;
            }
            respond(exchange, 404, "{}");
        });
        try {
            AlertResult result = template(server, false, 60, false, 24).evaluate(new AlertExecutionContext());

            assertEquals(AlertExecutionStatus.WARN, result.status());
            assertTrue(warnings(firstBranch(result)).contains("child_pipeline_not_success"));
            Map<String, Object> child = child(firstBranch(result), 0);
            assertTrue(warnings(child).contains("pipeline_not_success"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void findsFailedNestedChildrenAndIgnoresExternalDownstreams() throws Exception {
        HttpServer server = server(exchange -> {
            String path = path(exchange);
            if (path.startsWith("/api/v4/projects/7/pipelines?")) {
                respond(exchange, 200, "[" + pipeline(100, "success", "push", hoursAgo(1)) + "]");
                return;
            }
            if (path.equals("/api/v4/projects/7/pipelines/100/trigger_jobs?per_page=100&page=1")) {
                respond(exchange, 200, "[" + triggerJob(200, "success") + "," + triggerJob(999, "failed") + "]");
                return;
            }
            if (path.equals("/api/v4/projects/7/pipelines/200")) {
                respond(exchange, 200, pipeline(200, "success", "parent_pipeline", hoursAgo(1)));
                return;
            }
            if (path.equals("/api/v4/projects/7/pipelines/999")) {
                respond(exchange, 404, "{}");
                return;
            }
            if (path.equals("/api/v4/projects/7/pipelines/200/trigger_jobs?per_page=100&page=1")) {
                respond(exchange, 200, "[" + triggerJob(300, "canceled") + "]");
                return;
            }
            if (path.equals("/api/v4/projects/7/pipelines/300")) {
                respond(exchange, 200, pipeline(300, "canceled", "parent_pipeline", hoursAgo(1)));
                return;
            }
            respond(exchange, 404, "{}");
        });
        try {
            AlertResult result = template(server, false, 60, false, 24).evaluate(new AlertExecutionContext());

            assertEquals(AlertExecutionStatus.WARN, result.status());
            Map<String, Object> branch = firstBranch(result);
            assertEquals(1, branch.get("externalDownstreamCount"));
            Map<String, Object> grandchild = child(child(branch, 0), 0);
            assertEquals("canceled", grandchild.get("status"));
            assertTrue(warnings(grandchild).contains("pipeline_not_success"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ignoresChildPipelinesThatDidNotRun() throws Exception {
        HttpServer server = server(exchange -> {
            String path = path(exchange);
            if (path.startsWith("/api/v4/projects/7/pipelines?")) {
                respond(exchange, 200, "[" + pipeline(100, "success", "push", hoursAgo(1)) + "]");
                return;
            }
            if (path.equals("/api/v4/projects/7/pipelines/100/trigger_jobs?per_page=100&page=1")) {
                respond(exchange, 200, "[" + triggerJob(200, "skipped") + "]");
                return;
            }
            if (path.equals("/api/v4/projects/7/pipelines/200")) {
                respond(exchange, 200, pipeline(200, "skipped", "parent_pipeline", hoursAgo(1)));
                return;
            }
            respond(exchange, 404, "{}");
        });
        try {
            AlertResult result = template(server, false, 60, false, 24).evaluate(new AlertExecutionContext());

            assertEquals(AlertExecutionStatus.SUCCESS, result.status());
            assertEquals(true, child(firstBranch(result), 0).get("ignored"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void usesLegacyBridgesWhenTriggerJobsEndpointIsUnavailable() throws Exception {
        HttpServer server = server(exchange -> {
            String path = path(exchange);
            if (path.startsWith("/api/v4/projects/7/pipelines?")) {
                respond(exchange, 200, "[" + pipeline(100, "success", "push", hoursAgo(1)) + "]");
                return;
            }
            if (path.equals("/api/v4/projects/7/pipelines/100/trigger_jobs?per_page=100&page=1")) {
                respond(exchange, 404, "{}");
                return;
            }
            if (path.equals("/api/v4/projects/7/pipelines/100/bridges?per_page=100&page=1")) {
                respond(exchange, 200, "[]");
                return;
            }
            respond(exchange, 404, "{}");
        });
        try {
            AlertResult result = template(server, false, 60, false, 24).evaluate(new AlertExecutionContext());

            assertEquals(AlertExecutionStatus.SUCCESS, result.status());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void optionalTimeChecksWarnOnlyWhenEnabledAndExceeded() throws Exception {
        HttpServer server = server(exchange -> {
            String path = path(exchange);
            if (path.startsWith("/api/v4/projects/7/pipelines?")) {
                respond(exchange, 200, "[" + pipeline(100, "running", "push", hoursAgo(2)) + "]");
                return;
            }
            if (path.equals("/api/v4/projects/7/pipelines/100/trigger_jobs?per_page=100&page=1")) {
                respond(exchange, 200, "[]");
                return;
            }
            respond(exchange, 404, "{}");
        });
        try {
            AlertResult disabled = template(server, false, 60, false, 24).evaluate(new AlertExecutionContext());
            AlertResult enabled = template(server, true, 60, false, 24).evaluate(new AlertExecutionContext());

            assertEquals(AlertExecutionStatus.SUCCESS, disabled.status());
            assertEquals(AlertExecutionStatus.WARN, enabled.status());
            assertTrue(warnings(firstBranch(enabled)).contains("pipeline_active_too_long"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void freshnessWarnsOnlyForAnOldSuccessfulPipelineWhenEnabled() throws Exception {
        HttpServer server = server(exchange -> {
            String path = path(exchange);
            if (path.startsWith("/api/v4/projects/7/pipelines?")) {
                respond(exchange, 200, "[" + pipeline(100, "success", "push", hoursAgo(25)) + "]");
                return;
            }
            if (path.equals("/api/v4/projects/7/pipelines/100/trigger_jobs?per_page=100&page=1")) {
                respond(exchange, 200, "[]");
                return;
            }
            respond(exchange, 404, "{}");
        });
        try {
            AlertResult disabled = template(server, false, 60, false, 24).evaluate(new AlertExecutionContext());
            AlertResult enabled = template(server, false, 60, true, 24).evaluate(new AlertExecutionContext());

            assertEquals(AlertExecutionStatus.SUCCESS, disabled.status());
            assertEquals(AlertExecutionStatus.WARN, enabled.status());
            assertTrue(warnings(firstBranch(enabled)).contains("pipeline_stale"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void authenticationFailureIsSanitizedAndReportedAsWarn() throws Exception {
        HttpServer server = server(exchange -> respond(exchange, 401, "{\"message\":\"token " + TOKEN + " rejected\"}"));
        try {
            AlertExecutionContext context = new AlertExecutionContext();
            AlertResult result = template(server, false, 60, false, 24).evaluate(context);

            assertEquals(AlertExecutionStatus.WARN, result.status());
            assertEquals("auth_failed", firstBranch(result).get("failureReason"));
            assertEquals(401, firstBranch(result).get("statusCode"));
            assertFalse(result.statusMessage().toString().contains(TOKEN));
            assertFalse(context.getState().contains(TOKEN));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsInvalidConfigurationAndUnsupportedProviderDoesNotLeakTheToken() throws Exception {
        GitCredentials gitLab = credentials(GitProvider.GITLAB);
        assertThrows(IllegalArgumentException.class, () -> new GitLabPipelineAlertTemplate(gitLab, "7", "[]", false, 60, false, 24, 10));
        assertThrows(IllegalArgumentException.class, () -> new GitLabPipelineAlertTemplate(gitLab, "7", "[\"main\",\"main\"]", false, 60, false, 24, 10));
        assertThrows(IllegalArgumentException.class, () -> new GitLabPipelineAlertTemplate(gitLab, "https://gitlab.example/x", "[\"main\"]", false, 60, false, 24, 10));
        assertThrows(IllegalArgumentException.class, () -> new GitLabPipelineAlertTemplate(gitLab, "7", "[\"main\"]", false, 0, false, 24, 10));

        GitLabPipelineAlertTemplate unsupported = new GitLabPipelineAlertTemplate(
            credentials(GitProvider.GITHUB), "7", "[\"main\"]", false, 60, false, 24, 10,
            URI.create("http://127.0.0.1:1/api/v4/"), fixedClock()
        );
        AlertExecutionContext context = new AlertExecutionContext();
        AlertResult result = unsupported.evaluate(context);

        assertEquals(AlertExecutionStatus.WARN, result.status());
        assertEquals("unsupported_provider", result.statusMessage().get("failureReason"));
        assertFalse(result.statusMessage().toString().contains(TOKEN));
        assertFalse(context.getState().contains(TOKEN));
    }

    private static GitLabPipelineAlertTemplate template(HttpServer server, boolean checkActiveDuration, int maxActiveMinutes, boolean checkFreshness, int maxPipelineAgeHours) {
        return new GitLabPipelineAlertTemplate(
            credentials(GitProvider.GITLAB), "7", "[\"main\"]", checkActiveDuration, maxActiveMinutes,
            checkFreshness, maxPipelineAgeHours, 3,
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v4/"), fixedClock()
        );
    }

    private static GitCredentials credentials(GitProvider provider) {
        return new GitCredentials(provider, "gitlab.example", null, TOKEN, null);
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static String pipeline(long id, String status, String source, Instant createdAt) {
        return "{\"id\":" + id + ",\"status\":\"" + status + "\",\"ref\":\"main\",\"sha\":\"abc\",\"source\":\"" + source + "\",\"created_at\":\"" + createdAt + "\",\"updated_at\":\"" + createdAt + "\",\"web_url\":\"https://gitlab.example/pipelines/" + id + "\"}";
    }

    private static String triggerJob(long childId, String status) {
        return "{\"id\":" + (childId + 1) + ",\"downstream_pipeline\":{\"id\":" + childId + ",\"status\":\"" + status + "\"}}";
    }

    private static String ignoredPipelines(int count) {
        StringBuilder pipelines = new StringBuilder("[");
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                pipelines.append(',');
            }
            pipelines.append(pipeline(1_000 - index, "skipped", "push", hoursAgo(index + 1L)));
        }
        return pipelines.append(']').toString();
    }

    private static Instant hoursAgo(long hours) {
        return NOW.minusSeconds(hours * 3_600);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstBranch(AlertResult result) {
        return (Map<String, Object>) ((List<?>) result.statusMessage().get("branchResults")).get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> pipelineMessage(Map<String, Object> branch) {
        return (Map<String, Object>) branch.get("pipeline");
    }

    @SuppressWarnings("unchecked")
    private static List<String> warnings(Map<String, Object> message) {
        return (List<String>) message.get("warnings");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> child(Map<String, Object> message, int index) {
        return (Map<String, Object>) ((List<?>) message.get("childPipelines")).get(index);
    }

    private static String path(HttpExchange exchange) {
        String query = exchange.getRequestURI().getRawQuery();
        return exchange.getRequestURI().getRawPath() + (query == null ? "" : "?" + query);
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
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static AlertParameter parameter(String name) throws ReflectiveOperationException {
        return GitLabPipelineAlertTemplate.class.getDeclaredField(name).getAnnotation(AlertParameter.class);
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
