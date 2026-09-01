package app.alertify.alerts.templates;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import app.alertify.alerts.AlertEvaluator;
import app.alertify.alerts.AlertExecutionContext;
import app.alertify.alerts.AlertResult;
import app.alertify.alerts.template.annotation.AlertParameter;
import app.alertify.alerts.template.annotation.AlertTemplate;

/**
 * Standard alert that checks whether a public HTTP endpoint can be reached.
 * Its stable alternate key is this class' fully qualified name.
 */
@AlertTemplate(
    nameKey = "alerts.template.internet.name",
    descriptionKey = "alerts.template.internet.description",
    sourcePath = "app/alertify/alerts/templates/InternetConnectionAlertTemplate.java"
)
public final class InternetConnectionAlertTemplate implements AlertEvaluator {

    @AlertParameter(
        labelKey = "alerts.template.internet.endpoint",
        descriptionKey = "alerts.template.internet.endpointDescription",
        options = { "google", "cloudflare" },
        bindingAllowed = true,
        defaultValue = "google",
        order = 1
    )
    private final String endpoint;

    @AlertParameter(
        labelKey = "alerts.template.internet.timeout",
        descriptionKey = "alerts.template.internet.timeoutDescription",
        options = { "1", "3", "5", "10" },
        bindingAllowed = true,
        defaultValue = "3",
        order = 2
    )
    private final int timeoutSeconds;

    public InternetConnectionAlertTemplate(String endpoint, int timeoutSeconds) {
        this.endpoint = endpoint;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public AlertResult evaluate(AlertExecutionContext context) throws Exception {
        URI uri = endpoint(endpoint);
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("User-Agent", "Alertify/InternetConnectionAlert")
            .GET()
            .build();

        long startedNanos = System.nanoTime();
        HttpResponse<Void> response = client.send(
            request, HttpResponse.BodyHandlers.discarding()
        );
        long latencyMs = Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
        int statusCode = response.statusCode();
        Map<String, Object> statusMessage = new LinkedHashMap<>();
        statusMessage.put("endpoint", uri.toString());
        statusMessage.put("statusCode", statusCode);
        statusMessage.put("latencyMs", latencyMs);
        statusMessage.put("checkedAt", Instant.now().toString());
        context.setState(
            "endpoint=" + uri + ";statusCode=" + statusCode + ";latencyMs=" + latencyMs
        );

        if (statusCode >= 200 && statusCode < 400)
            return AlertResult.success(statusMessage);

        return AlertResult.warn(statusMessage);
    }

    private static URI endpoint(String configured) {
        return switch (configured) {
            case "google" -> URI.create("https://clients3.google.com/generate_204");
            case "cloudflare" -> URI.create("https://www.cloudflare.com/cdn-cgi/trace");
            default -> URI.create(configured);
        };
    }
}
