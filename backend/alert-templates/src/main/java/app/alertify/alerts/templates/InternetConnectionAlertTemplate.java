package app.alertify.alerts.templates;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import app.alertify.alerts.AlertEvaluator;
import app.alertify.alerts.AlertExecutionContext;
import app.alertify.alerts.AlertResult;
import app.alertify.alerts.template.annotation.AlertParameter;
import app.alertify.alerts.template.annotation.AlertTemplate;
import app.alertify.alerts.template.annotation.AlertTemplateTag;

/**
 * Standard alert that checks whether a public HTTP endpoint can be reached.
 * Its stable alternate key is this class' fully qualified name.
 */
@AlertTemplate(
    nameKey = "alerts.template.internet.name",
    descriptionKey = "alerts.template.internet.description",
    tags = @AlertTemplateTag(nameKey = "alerts.templateTag.network", color = "#0EA5E9"),
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
        HttpResponse<Void> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (HttpTimeoutException exception) {
            long latencyMs = elapsedMillis(startedNanos);
            Map<String, Object> statusMessage = statusMessage(uri, timeout, latencyMs);
            statusMessage.put("timedOut", true);
            context.setState("endpoint=" + uri + ";timeoutSeconds=" + timeoutSeconds + ";latencyMs=" + latencyMs + ";timedOut=true");
            return AlertResult.warn(statusMessage);
        }

        long latencyMs = elapsedMillis(startedNanos);
        int statusCode = response.statusCode();
        Map<String, Object> statusMessage = statusMessage(uri, timeout, latencyMs);
        statusMessage.put("statusCode", statusCode);
        context.setState("endpoint=" + uri + ";statusCode=" + statusCode + ";timeoutSeconds=" + timeoutSeconds + ";latencyMs=" + latencyMs);

        if (statusCode >= 200 && statusCode < 400)
            return AlertResult.success(statusMessage);

        return AlertResult.warn(statusMessage);
    }

    private static Map<String, Object> statusMessage(URI uri, Duration timeout, long latencyMs) {
        Map<String, Object> statusMessage = new LinkedHashMap<>();
        statusMessage.put("endpoint", uri.toString());
        statusMessage.put("timeoutSeconds", timeout.toSeconds());
        statusMessage.put("latencyMs", latencyMs);
        statusMessage.put("checkedAt", Instant.now().toString());
        return statusMessage;
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    private static URI endpoint(String configured) {
        return switch (configured) {
            case "google" -> URI.create("https://clients3.google.com/generate_204");
            case "cloudflare" -> URI.create("https://www.cloudflare.com/cdn-cgi/trace");
            default -> URI.create(configured);
        };
    }
}
