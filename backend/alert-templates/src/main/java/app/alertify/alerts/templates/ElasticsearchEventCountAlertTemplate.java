package app.alertify.alerts.templates;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLException;

import app.alertify.alerts.AlertEvaluator;
import app.alertify.alerts.AlertExecutionContext;
import app.alertify.alerts.AlertResult;
import app.alertify.alerts.template.annotation.AlertParameter;
import app.alertify.alerts.template.annotation.AlertTemplate;
import app.alertify.alerts.template.annotation.AlertTemplateTag;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Counts matching events using the Elasticsearch 7+ REST count API. */
@AlertTemplate(
    nameKey = "alerts.template.elasticsearchEventCount.name",
    descriptionKey = "alerts.template.elasticsearchEventCount.description",
    tags = @AlertTemplateTag(nameKey = "alerts.templateTag.network", color = "#0EA5E9"),
    sourcePath = "app/alertify/alerts/templates/ElasticsearchEventCountAlertTemplate.java"
)
public final class ElasticsearchEventCountAlertTemplate implements AlertEvaluator {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final int MAX_QUERY_BYTES = 1024 * 1024;

    @AlertParameter(
        labelKey = "alerts.template.elasticsearchEventCount.url",
        descriptionKey = "alerts.template.elasticsearchEventCount.urlDescription",
        order = 1
    )
    private final String url;

    @AlertParameter(
        labelKey = "alerts.template.elasticsearchEventCount.headersJson",
        descriptionKey = "alerts.template.elasticsearchEventCount.headersJsonDescription",
        multiline = true,
        required = false,
        defaultValue = "[]",
        order = 2
    )
    private final String headersJson;

    @AlertParameter(
        labelKey = "alerts.template.elasticsearchEventCount.indices",
        descriptionKey = "alerts.template.elasticsearchEventCount.indicesDescription",
        order = 3
    )
    private final String indices;

    @AlertParameter(
        labelKey = "alerts.template.elasticsearchEventCount.queryJson",
        descriptionKey = "alerts.template.elasticsearchEventCount.queryJsonDescription",
        multiline = true,
        defaultValue = "{\"match_all\":{}}",
        order = 4
    )
    private final String queryJson;

    @AlertParameter(
        labelKey = "alerts.template.elasticsearchEventCount.timeField",
        descriptionKey = "alerts.template.elasticsearchEventCount.timeFieldDescription",
        defaultValue = "@timestamp",
        order = 5
    )
    private final String timeField;

    @AlertParameter(
        labelKey = "alerts.template.elasticsearchEventCount.windowMinutes",
        descriptionKey = "alerts.template.elasticsearchEventCount.windowMinutesDescription",
        defaultValue = "5",
        order = 6
    )
    private final int windowMinutes;

    @AlertParameter(
        labelKey = "alerts.template.elasticsearchEventCount.thresholdType",
        descriptionKey = "alerts.template.elasticsearchEventCount.thresholdTypeDescription",
        options = { "warn_if_bigger", "warn_if_lower", "warn_if_equal", "warn_if_distinct" },
        bindingAllowed = false,
        defaultValue = "warn_if_bigger",
        order = 7
    )
    private final String thresholdType;

    @AlertParameter(
        labelKey = "alerts.template.elasticsearchEventCount.threshold",
        descriptionKey = "alerts.template.elasticsearchEventCount.thresholdDescription",
        order = 8
    )
    private final long threshold;

    @AlertParameter(
        labelKey = "alerts.template.elasticsearchEventCount.evaluationDelaySeconds",
        descriptionKey = "alerts.template.elasticsearchEventCount.evaluationDelaySecondsDescription",
        defaultValue = "0",
        order = 9
    )
    private final int evaluationDelaySeconds;

    @AlertParameter(
        labelKey = "alerts.template.elasticsearchEventCount.timeoutSeconds",
        descriptionKey = "alerts.template.elasticsearchEventCount.timeoutSecondsDescription",
        defaultValue = "10",
        order = 10
    )
    private final int timeoutSeconds;

    private final URI endpoint;
    private final Clock clock;

    public ElasticsearchEventCountAlertTemplate(String url, String headersJson, String indices, String queryJson, String timeField, int windowMinutes, String thresholdType, long threshold, int evaluationDelaySeconds, int timeoutSeconds) {
        this(url, headersJson, indices, queryJson, timeField, windowMinutes, thresholdType, threshold, evaluationDelaySeconds, timeoutSeconds, Clock.systemUTC());
    }

    ElasticsearchEventCountAlertTemplate(String url, String headersJson, String indices, String queryJson, String timeField, int windowMinutes, String thresholdType, long threshold, int evaluationDelaySeconds, int timeoutSeconds, Clock clock) {
        this.url = requireText(url, "url");
        this.headersJson = headersJson;
        this.indices = requireText(indices, "indices");
        this.queryJson = requireText(queryJson, "queryJson");
        this.timeField = requireText(timeField, "timeField");
        if (windowMinutes <= 0 || evaluationDelaySeconds < 0 || threshold < 0 || timeoutSeconds <= 0 || timeoutSeconds > 3600)
            throw new IllegalArgumentException("Window and timeout must be positive, delay and threshold nonnegative; timeout must not exceed 3600 seconds");

        if (thresholdType == null || !java.util.Set.of("warn_if_bigger", "warn_if_lower", "warn_if_equal", "warn_if_distinct").contains(thresholdType))
            throw new IllegalArgumentException("Unsupported thresholdType");

        this.windowMinutes = windowMinutes;
        this.thresholdType = thresholdType;
        this.threshold = threshold;
        this.evaluationDelaySeconds = evaluationDelaySeconds;
        this.timeoutSeconds = timeoutSeconds;
        this.clock = java.util.Objects.requireNonNull(clock);
        this.endpoint = endpoint(this.url, this.indices);
    }

    @Override
    public AlertResult evaluate(AlertExecutionContext context) throws InterruptedException {
        Instant checkedAt = clock.instant();
        Instant to = checkedAt.minusSeconds(evaluationDelaySeconds);
        Instant from = to.minus(Duration.ofMinutes(windowMinutes));
        String body = requestBody(from, to);
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        addHeaders(builder);
        HttpRequest request;
        try {
            request = builder.build();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid HTTP header configuration");
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("checkedAt", checkedAt.toString());
        message.put("windowFrom", from.toString());
        message.put("windowTo", to.toString());
        message.put("windowMinutes", windowMinutes);
        message.put("evaluationDelaySeconds", evaluationDelaySeconds);
        message.put("threshold", threshold);
        message.put("thresholdType", thresholdType);
        message.put("timeoutSeconds", timeoutSeconds);
        long started = System.nanoTime();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).followRedirects(HttpClient.Redirect.NEVER).build()) {
            var responseFuture = client.sendAsync(request, info -> info.statusCode() == 200
                ? HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), MAX_RESPONSE_BYTES).apply(info)
                : HttpResponse.BodySubscribers.replacing(new byte[0]));
            HttpResponse<byte[]> response;
            try {
                response = responseFuture.get(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
            } catch (TimeoutException exception) {
                responseFuture.cancel(true);
                return warning(context, message, "timeout", started);
            } catch (InterruptedException exception) {
                responseFuture.cancel(true);
                Thread.currentThread().interrupt();
                throw exception;
            }
            message.put("statusCode", response.statusCode());
            if (response.statusCode() != 200) {
                String reason = switch (response.statusCode()) {
                    case 401 -> "auth_failed";
                    case 403 -> "elasticsearch_access_denied";
                    case 404 -> "elasticsearch_index_unavailable";
                    case 400 -> "query_failure";
                    default -> "elasticsearch_http_failure";
                };
                return warning(context, message, reason, started);
            }
            JsonNode root;
            try {
                root = JSON.readTree(response.body());
            } catch (RuntimeException exception) {
                return warning(context, message, "elasticsearch_invalid_response", started);
            }
            if (root == null || !root.isObject() || !nonnegativeLong(root.path("count")) || !root.path("_shards").isObject())
                return warning(context, message, "elasticsearch_invalid_response", started);

            JsonNode shards = root.path("_shards");
            if (!nonnegativeLong(shards.path("total")) || !nonnegativeLong(shards.path("successful")) || !nonnegativeLong(shards.path("failed")))
                return warning(context, message, "elasticsearch_invalid_response", started);

            if (shards.path("total").longValue() == 0 || shards.path("failed").longValue() != 0 || shards.path("successful").longValue() != shards.path("total").longValue())
                return warning(context, message, "elasticsearch_partial_result", started);

            if (root.path("timed_out").asBoolean(false) || root.path("terminated_early").asBoolean(false))
                return warning(context, message, "elasticsearch_partial_result", started);

            long count = root.path("count").longValue();
            boolean warn = switch (thresholdType) {
                case "warn_if_bigger" -> count > threshold;
                case "warn_if_lower" -> count < threshold;
                case "warn_if_equal" -> count == threshold;
                default -> count != threshold;
            };
            message.put("count", count);
            message.put("latencyMs", elapsedMillis(started));
            context.setState("count=" + count + ";threshold=" + threshold + ";thresholdType=" + thresholdType + ";status=" + (warn ? "WARN" : "SUCCESS"));
            return warn ? AlertResult.warn(message) : AlertResult.success(message);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            String reason = cause instanceof HttpTimeoutException ? "timeout"
                : cause instanceof SSLException ? "elasticsearch_tls_failure"
                : cause instanceof IOException ? "connection_failure" : "elasticsearch_http_failure";
            return warning(context, message, reason, started);
        }
    }

    private String requestBody(Instant from, Instant to) {
        if (queryJson.getBytes(StandardCharsets.UTF_8).length > MAX_QUERY_BYTES)
            throw new IllegalArgumentException("Query DSL must not exceed 1 MiB");

        JsonNode query;
        try {
            query = JSON.readTree(queryJson);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("queryJson must contain a valid Query DSL object");
        }
        if (query == null || !query.isObject() || query.size() != 1 || query.has("query"))
            throw new IllegalArgumentException("queryJson must contain one Query DSL clause, without a query wrapper");

        var root = JSON.createObjectNode();
        var filters = root.putObject("query").putObject("bool").putArray("filter");
        filters.add(query);
        filters.addObject().putObject("range").putObject(timeField)
            .put("gte", from.toString()).put("lt", to.toString()).put("format", "strict_date_optional_time");
        return JSON.writeValueAsString(root);
    }

    private void addHeaders(HttpRequest.Builder builder) {
        if (headersJson == null || headersJson.isBlank())
            return;

        try {
            JsonNode headers = JSON.readTree(headersJson);
            if (headers == null || !headers.isArray())
                throw new IllegalArgumentException();

            for (JsonNode header : headers) {
                if (!header.isString())
                    throw new IllegalArgumentException();

                String line = header.asText();
                int separator = line.indexOf(':');
                if (separator <= 0 || line.indexOf('\r') >= 0 || line.indexOf('\n') >= 0)
                    throw new IllegalArgumentException();

                builder.setHeader(line.substring(0, separator).trim(), line.substring(separator + 1).trim());
            }
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("headersJson must be a JSON array of valid Name: value HTTP headers");
        }
    }

    private static URI endpoint(String configuredUrl, String indices) {
        try {
            URI base = new URI(configuredUrl);
            if (!("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme())) || base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null)
                throw new IllegalArgumentException();

            if (indices.chars().anyMatch(character -> Character.isWhitespace(character) || "/\\?#%<>".indexOf(character) >= 0) || indices.equals(".") || indices.equals("..") || indices.contains(",,") || indices.startsWith(",") || indices.endsWith(","))
                throw new IllegalArgumentException();

            String target = URLEncoder.encode(indices, StandardCharsets.UTF_8).replace("%2C", ",").replace("%3A", ":");
            return URI.create(configuredUrl.replaceAll("/+$", "") + "/" + target + "/_count?allow_no_indices=false&ignore_unavailable=false");
        } catch (URISyntaxException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid Elasticsearch base URL or index target; URL credentials, queries and fragments are not allowed");
        }
    }

    private static boolean nonnegativeLong(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0;
    }

    private static AlertResult warning(AlertExecutionContext context, Map<String, Object> message, String reason, long started) {
        message.put("failureReason", reason);
        message.put("latencyMs", elapsedMillis(started));
        context.setState("status=WARN;failure=" + reason);
        return AlertResult.warn(message);
    }

    private static long elapsedMillis(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(name + " must not be blank");

        return value.trim();
    }
}
