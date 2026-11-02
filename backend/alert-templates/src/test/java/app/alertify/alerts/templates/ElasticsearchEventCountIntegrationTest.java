package app.alertify.alerts.templates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import app.alertify.alerts.AlertExecutionContext;
import app.alertify.alerts.execution.AlertExecutionStatus;

/** Run only against an explicitly configured disposable Elasticsearch instance. */
@EnabledIfEnvironmentVariable(named = "ALERTIFY_ELASTICSEARCH_TEST_URL", matches = ".+")
class ElasticsearchEventCountIntegrationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-01T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void countsRealIndexedEventsWithTimeBoundariesFiltersAndAbsence() throws Exception {
        String url = System.getenv("ALERTIFY_ELASTICSEARCH_TEST_URL");
        String index = "alertify-event-count-" + UUID.randomUUID();
        try (HttpClient client = HttpClient.newHttpClient()) {
            try {
                assertEquals(200, request(client, url + "/" + index, "PUT", "{\"mappings\":{\"properties\":{\"@timestamp\":{\"type\":\"date\"},\"created\":{\"type\":\"date\"},\"level\":{\"type\":\"keyword\"}}}}"));
                String[] times = { "11:54:00", "11:58:00", "11:59:00", "11:53:59" };
                for (String time : times) {
                    String timestamp = "2026-11-01T" + time + "Z";
                    assertEquals(201, request(client, url + "/" + index + "/_doc?refresh=true", "POST", "{\"@timestamp\":\"" + timestamp + "\",\"created\":\"" + timestamp + "\",\"level\":\"ERROR\"}"));
                }
                var count = template(url, index, "@timestamp", "{\"term\":{\"level\":\"ERROR\"}}", "warn_if_bigger", 1).evaluate(new AlertExecutionContext());
                assertEquals(AlertExecutionStatus.WARN, count.status());
                assertEquals(2L, count.statusMessage().get("count"));
                var absent = template(url, index, "@timestamp", "{\"term\":{\"level\":\"INFO\"}}", "warn_if_equal", 0).evaluate(new AlertExecutionContext());
                assertEquals(AlertExecutionStatus.WARN, absent.status());
                assertEquals(0L, absent.statusMessage().get("count"));
                var custom = template(url, index, "created", "{\"match_all\":{}}", "warn_if_equal", 0).evaluate(new AlertExecutionContext());
                assertEquals(AlertExecutionStatus.SUCCESS, custom.status());
                assertEquals(2L, custom.statusMessage().get("count"));
                var missing = template(url, index + "-missing-*", "@timestamp", "{\"match_all\":{}}", "warn_if_equal", 0).evaluate(new AlertExecutionContext());
                assertEquals(AlertExecutionStatus.WARN, missing.status());
                assertFalse(missing.statusMessage().containsKey("count"));
                var invalid = template(url, index, "@timestamp", "{\"invalid_clause\":{}}", "warn_if_equal", 0).evaluate(new AlertExecutionContext());
                assertEquals("query_failure", invalid.statusMessage().get("failureReason"));
                assertFalse(invalid.statusMessage().containsKey("count"));
            } finally {
                assertEquals(200, request(client, url + "/" + index, "DELETE", ""));
            }
        }
    }

    private static ElasticsearchEventCountAlertTemplate template(String url, String index, String timeField, String query, String comparison, long threshold) {
        return new ElasticsearchEventCountAlertTemplate(url, "[]", index, query, timeField, 5, comparison, threshold, 60, 10, CLOCK);
    }

    private static int request(HttpClient client, String url, String method, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
