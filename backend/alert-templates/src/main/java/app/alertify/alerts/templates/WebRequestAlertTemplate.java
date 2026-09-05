package app.alertify.alerts.templates;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import app.alertify.alerts.AlertEvaluator;
import app.alertify.alerts.AlertExecutionContext;
import app.alertify.alerts.AlertResult;
import app.alertify.alerts.template.annotation.AlertParameter;
import app.alertify.alerts.template.annotation.AlertTemplate;
import app.alertify.alerts.template.annotation.AlertTemplateTag;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends a configurable HTTP request and validates its response status and
 * optional response-body regular expressions.
 */
@AlertTemplate(
    nameKey = "alerts.template.webRequest.name",
    descriptionKey = "alerts.template.webRequest.description",
    tags = @AlertTemplateTag(nameKey = "alerts.templateTag.network", color = "#0EA5E9"),
    sourcePath = "app/alertify/alerts/templates/WebRequestAlertTemplate.java"
)
public final class WebRequestAlertTemplate implements AlertEvaluator {

    private static final int MAX_BODY_BYTES = 10 * 1024 * 1024;
    private static final Set<String> METHODS = Set.of(
        "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"
    );
    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @AlertParameter(
        labelKey = "alerts.template.webRequest.url",
        descriptionKey = "alerts.template.webRequest.urlDescription",
        order = 1
    )
    private final String url;

    @AlertParameter(
        labelKey = "alerts.template.webRequest.method",
        descriptionKey = "alerts.template.webRequest.methodDescription",
        options = { "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS" },
        bindingAllowed = false,
        defaultValue = "GET",
        order = 2
    )
    private final String method;

    @AlertParameter(
        labelKey = "alerts.template.webRequest.expectedStatusCodes",
        descriptionKey = "alerts.template.webRequest.expectedStatusCodesDescription",
        defaultValue = "200",
        order = 3
    )
    private final String expectedStatusCodes;

    @AlertParameter(
        labelKey = "alerts.template.webRequest.body",
        descriptionKey = "alerts.template.webRequest.bodyDescription",
        multiline = true,
        required = false,
        order = 4
    )
    private final String body;

    @AlertParameter(
        labelKey = "alerts.template.webRequest.headersJson",
        descriptionKey = "alerts.template.webRequest.headersJsonDescription",
        defaultValue = "[]",
        multiline = true,
        order = 5
    )
    private final String headersJson;

    @AlertParameter(
        labelKey = "alerts.template.webRequest.responseBodyRegexes",
        descriptionKey = "alerts.template.webRequest.responseBodyRegexesDescription",
        multiline = true,
        required = false,
        order = 6
    )
    private final String responseBodyRegexes;

    @AlertParameter(
        labelKey = "alerts.template.webRequest.timeout",
        descriptionKey = "alerts.template.webRequest.timeoutDescription",
        options = { "1", "3", "5", "10", "30" },
        defaultValue = "10",
        order = 7
    )
    private final int timeoutSeconds;

    public WebRequestAlertTemplate(
        String url,
        String method,
        String expectedStatusCodes,
        String body,
        String headersJson,
        String responseBodyRegexes,
        int timeoutSeconds
    ) {
        this.url = url;
        this.method = method;
        this.expectedStatusCodes = expectedStatusCodes;
        this.body = body;
        this.headersJson = headersJson;
        this.responseBodyRegexes = responseBodyRegexes;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public AlertResult evaluate(AlertExecutionContext context) throws Exception {
        URI uri = parseUri(url);
        String requestMethod = parseMethod(method);
        List<Integer> expectedCodes = parseExpectedStatusCodes(expectedStatusCodes);
        List<Header> headers = parseHeaders(headersJson);
        List<Pattern> bodyPatterns = parsePatterns(responseBodyRegexes);
        Duration timeout = timeout(timeoutSeconds);
        byte[] requestBody = requestBody(body, requestMethod);
        String endpoint = safeEndpoint(uri);

        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("User-Agent", "Alertify/WebRequest");
        for (Header header : headers)
            request.header(header.name(), header.value());

        request.method(
            requestMethod,
            requestBody.length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(requestBody)
        );

        long startedNanos = System.nanoTime();
        try {
            HttpResponse<InputStream> response = client.send(
                request.build(), HttpResponse.BodyHandlers.ofInputStream()
            );
            ResponseBody responseBody = readResponseBody(response);
            long latencyMs = elapsedMillis(startedNanos);
            Map<String, Object> statusMessage = statusMessage(
                endpoint, requestMethod, expectedCodes, timeout, latencyMs
            );
            statusMessage.put("statusCode", response.statusCode());
            statusMessage.put("responseBodyBytes", responseBody.bytes().length);
            statusMessage.put("regexCount", bodyPatterns.size());

            if (!expectedCodes.contains(response.statusCode())) {
                return warn(context, statusMessage, "unexpectedStatusCode", endpoint, requestMethod);
            }

            String responseText = new String(responseBody.bytes(), StandardCharsets.UTF_8);
            for (int index = 0; index < bodyPatterns.size(); index++) {
                if (!bodyPatterns.get(index).matcher(responseText).find()) {
                    statusMessage.put("unmatchedRegexIndex", index);
                    return warn(context, statusMessage, "responseBodyRegexMismatch", endpoint, requestMethod);
                }
            }

            context.setState("method=" + requestMethod + ";endpoint=" + endpoint + ";status=SUCCESS");
            return AlertResult.success(statusMessage);
        } catch (HttpTimeoutException exception) {
            return connectionWarning(context, endpoint, requestMethod, expectedCodes, timeout, startedNanos, "timeout");
        } catch (ResponseBodyTooLargeException exception) {
            return connectionWarning(context, endpoint, requestMethod, expectedCodes, timeout, startedNanos, "responseBodyTooLarge");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw exception;
        } catch (IOException exception) {
            return connectionWarning(context, endpoint, requestMethod, expectedCodes, timeout, startedNanos, "connectionFailure");
        }
    }

    private static AlertResult connectionWarning(
        AlertExecutionContext context,
        String endpoint,
        String method,
        List<Integer> expectedCodes,
        Duration timeout,
        long startedNanos,
        String failureReason
    ) {
        Map<String, Object> statusMessage = statusMessage(
            endpoint, method, expectedCodes, timeout, elapsedMillis(startedNanos)
        );
        return warn(context, statusMessage, failureReason, endpoint, method);
    }

    private static AlertResult warn(
        AlertExecutionContext context,
        Map<String, Object> statusMessage,
        String failureReason,
        String endpoint,
        String method
    ) {
        statusMessage.put("failureReason", failureReason);
        context.setState(
            "method=" + method + ";endpoint=" + endpoint + ";status=WARN;failure=" + failureReason
        );
        return AlertResult.warn(statusMessage);
    }

    private static URI parseUri(String configured) {
        try {
            URI uri = URI.create(requireText(configured, "url"));
            if (!uri.isAbsolute() || uri.getHost() == null
                    || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException("url must be an absolute HTTP or HTTPS URL");
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid url", exception);
        }
    }

    private static String parseMethod(String configured) {
        String parsed = requireText(configured, "method").toUpperCase(Locale.ROOT);
        if (!METHODS.contains(parsed))
            throw new IllegalArgumentException("Unsupported HTTP method: " + parsed);

        return parsed;
    }

    private static List<Integer> parseExpectedStatusCodes(String configured) {
        LinkedHashSet<Integer> codes = new LinkedHashSet<>();
        for (String value : requireText(configured, "expectedStatusCodes").split(",", -1)) {
            try {
                int code = Integer.parseInt(value.trim());
                if (code < 100 || code > 599)
                    throw new IllegalArgumentException("Expected HTTP status codes must be between 100 and 599");

                if (!codes.add(code))
                    throw new IllegalArgumentException("Expected HTTP status codes must not contain duplicates");
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Expected HTTP status codes must be comma-separated integers", exception);
            }
        }
        return List.copyOf(codes);
    }

    private static List<Header> parseHeaders(String configured) {
        String json = configured == null || configured.isBlank() ? "[]" : configured;
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("headersJson must be a JSON array of strings", exception);
        }
        if (root == null || !root.isArray())
            throw new IllegalArgumentException("headersJson must be a JSON array of strings");

        List<Header> headers = new ArrayList<>();
        for (JsonNode item : root) {
            if (!item.isTextual())
                throw new IllegalArgumentException("headersJson must contain only strings");

            String header = item.textValue();
            int separator = header.indexOf(':');
            if (separator <= 0)
                throw new IllegalArgumentException("Each header must use the format 'Name: value'");

            String name = header.substring(0, separator).trim();
            String value = header.substring(separator + 1).trim();
            if (name.isEmpty() || name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0
                    || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("HTTP headers must not be blank or contain line breaks");
            }
            headers.add(new Header(name, value));
        }
        return List.copyOf(headers);
    }

    private static List<Pattern> parsePatterns(String configured) {
        if (configured == null || configured.isBlank())
            return List.of();

        return configured.lines()
            .filter(line -> !line.isBlank())
            .map(Pattern::compile)
            .toList();
    }

    private static Duration timeout(int seconds) {
        if (seconds <= 0)
            throw new IllegalArgumentException("timeoutSeconds must be positive");

        return Duration.ofSeconds(seconds);
    }

    private static byte[] requestBody(String configured, String method) {
        String value = configured == null ? "" : configured;
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BODY_BYTES)
            throw new IllegalArgumentException("Request body must not exceed 10 MiB");

        if (bytes.length > 0 && !BODY_METHODS.contains(method)) {
            throw new IllegalArgumentException("HTTP method " + method + " does not support a request body");
        }
        return bytes;
    }

    private static ResponseBody readResponseBody(HttpResponse<InputStream> response) throws IOException {
        long contentLength = response.headers().firstValueAsLong("content-length").orElse(-1);
        if (contentLength > MAX_BODY_BYTES)
            throw new ResponseBodyTooLargeException();

        try (InputStream input = response.body(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8_192];
            int total = 0;
            for (int read; (read = input.read(buffer)) >= 0;) {
                total += read;
                if (total > MAX_BODY_BYTES)
                    throw new ResponseBodyTooLargeException();

                output.write(buffer, 0, read);
            }
            return new ResponseBody(output.toByteArray());
        }
    }

    private static Map<String, Object> statusMessage(
        String endpoint,
        String method,
        List<Integer> expectedStatusCodes,
        Duration timeout,
        long latencyMs
    ) {
        Map<String, Object> statusMessage = new LinkedHashMap<>();
        statusMessage.put("endpoint", endpoint);
        statusMessage.put("method", method);
        statusMessage.put("expectedStatusCodes", expectedStatusCodes);
        statusMessage.put("timeoutSeconds", timeout.toSeconds());
        statusMessage.put("latencyMs", latencyMs);
        statusMessage.put("checkedAt", Instant.now().toString());
        return statusMessage;
    }

    private static String safeEndpoint(URI uri) {
        try {
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getRawPath(), null, null).toString();
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Could not sanitize configured URL", exception);
        }
    }

    private static String requireText(String value, String parameter) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(parameter + " must not be blank");

        return value;
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    private record Header(String name, String value) {
    }

    private record ResponseBody(byte[] bytes) {
    }

    private static final class ResponseBodyTooLargeException extends IOException {
    }
}
