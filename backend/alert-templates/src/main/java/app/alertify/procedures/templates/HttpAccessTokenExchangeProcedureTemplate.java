package app.alertify.procedures.templates;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.procedures.ProcedureEvaluator;
import app.alertify.procedures.ProcedureExecutionContext;
import app.alertify.procedures.template.annotation.ProcedureParameter;
import app.alertify.procedures.template.annotation.ProcedureTemplate;
import app.alertify.procedures.template.annotation.ProcedureTemplateTag;
import app.alertify.worker.contract.UsernamePasswordCredentials;
import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ProcedureTemplate(
    nameKey = "procedures.template.httpAccessTokenExchange.name",
    descriptionKey = "procedures.template.httpAccessTokenExchange.description",
    tags = @ProcedureTemplateTag(nameKey = "procedures.templateTag.security", color = "#7C3AED"),
    sourcePath = "app/alertify/procedures/templates/HttpAccessTokenExchangeProcedureTemplate.java",
    sensitiveResult = true
)
public final class HttpAccessTokenExchangeProcedureTemplate implements ProcedureEvaluator {
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @ProcedureParameter(
        labelKey = "procedures.template.httpAccessTokenExchange.credentials",
        descriptionKey = "procedures.template.httpAccessTokenExchange.credentialsDescription",
        allowedSources = AlertParameterSource.SECRET,
        allowedSecretValueTypes = "USERNAME_PASSWORD",
        order = 1
    )
    private final UsernamePasswordCredentials credentials;

    @ProcedureParameter(
        labelKey = "procedures.template.httpAccessTokenExchange.tokenUrl",
        descriptionKey = "procedures.template.httpAccessTokenExchange.tokenUrlDescription",
        order = 2
    )
    private final String tokenUrl;

    @ProcedureParameter(
        labelKey = "procedures.template.httpAccessTokenExchange.authenticationType",
        descriptionKey = "procedures.template.httpAccessTokenExchange.authenticationTypeDescription",
        options = { "BASIC", "OAUTH2_PASSWORD" },
        bindingAllowed = false,
        order = 3
    )
    private final String authenticationType;

    @ProcedureParameter(
        labelKey = "procedures.template.httpAccessTokenExchange.basicMethod",
        descriptionKey = "procedures.template.httpAccessTokenExchange.basicMethodDescription",
        options = { "GET", "POST" },
        bindingAllowed = false,
        defaultValue = "POST",
        order = 4
    )
    private final String basicMethod;

    @ProcedureParameter(
        labelKey = "procedures.template.httpAccessTokenExchange.scope",
        descriptionKey = "procedures.template.httpAccessTokenExchange.scopeDescription",
        required = false,
        order = 5
    )
    private final String scope;

    @ProcedureParameter(
        labelKey = "procedures.template.httpAccessTokenExchange.clientAuthenticationType",
        descriptionKey = "procedures.template.httpAccessTokenExchange.clientAuthenticationTypeDescription",
        options = { "NONE", "CLIENT_ID_BODY", "BASIC" },
        bindingAllowed = false,
        defaultValue = "NONE",
        order = 6
    )
    private final String clientAuthenticationType;

    @ProcedureParameter(
        labelKey = "procedures.template.httpAccessTokenExchange.clientId",
        descriptionKey = "procedures.template.httpAccessTokenExchange.clientIdDescription",
        required = false,
        order = 7
    )
    private final String clientId;

    @ProcedureParameter(
        labelKey = "procedures.template.httpAccessTokenExchange.clientCredentials",
        descriptionKey = "procedures.template.httpAccessTokenExchange.clientCredentialsDescription",
        allowedSources = AlertParameterSource.SECRET,
        allowedSecretValueTypes = "USERNAME_PASSWORD",
        required = false,
        order = 8
    )
    private final UsernamePasswordCredentials clientCredentials;

    @ProcedureParameter(
        labelKey = "procedures.template.httpAccessTokenExchange.tokenJsonPointer",
        descriptionKey = "procedures.template.httpAccessTokenExchange.tokenJsonPointerDescription",
        defaultValue = "/access_token",
        order = 9
    )
    private final String tokenJsonPointer;

    @ProcedureParameter(
        labelKey = "procedures.template.httpAccessTokenExchange.timeoutSeconds",
        descriptionKey = "procedures.template.httpAccessTokenExchange.timeoutSecondsDescription",
        defaultValue = "10",
        order = 10
    )
    private final Integer timeoutSeconds;

    public HttpAccessTokenExchangeProcedureTemplate(UsernamePasswordCredentials credentials, String tokenUrl, String authenticationType, String basicMethod, String scope, String clientAuthenticationType, String clientId, UsernamePasswordCredentials clientCredentials, String tokenJsonPointer, Integer timeoutSeconds) {
        this.credentials = credentials;
        this.tokenUrl = tokenUrl;
        this.authenticationType = authenticationType;
        this.basicMethod = basicMethod;
        this.scope = scope;
        this.clientAuthenticationType = clientAuthenticationType;
        this.clientId = clientId;
        this.clientCredentials = clientCredentials;
        this.tokenJsonPointer = tokenJsonPointer;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public JsonNode execute(ProcedureExecutionContext context) throws Exception {
        RequestConfiguration configuration = validate();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(configuration.timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        HttpRequest request = request(configuration);
        HttpResponse<byte[]> response;
        try {
            response = client.send(request, _ -> new LimitedBodySubscriber());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Token endpoint request interrupted");
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Token endpoint request failed");
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IllegalStateException("Token endpoint returned HTTP " + response.statusCode());

        if (!isJson(response.headers().firstValue("Content-Type").orElse("")))
            throw new IllegalStateException("Token endpoint did not return JSON");

        JsonNode selected;
        try {
            selected = JSON.readTree(response.body()).at(configuration.tokenJsonPointer());
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Token endpoint returned invalid JSON");
        }
        if (!selected.isString() || selected.stringValue().isBlank())
            throw new IllegalStateException("Token endpoint response did not contain a non-empty textual token");

        String token = selected.stringValue();
        if (token.chars().anyMatch(Character::isISOControl))
            throw new IllegalStateException("Token endpoint returned a token with invalid control characters");

        return JSON.createObjectNode().put("accessToken", token);
    }

    private RequestConfiguration validate() {
        if (credentials == null)
            throw new IllegalArgumentException("credentials is required");

        URI uri = uri(tokenUrl);
        Duration timeout = timeout(timeoutSeconds);
        String pointerValue = required(tokenJsonPointer, "tokenJsonPointer");
        if (!pointerValue.startsWith("/") || pointerValue.length() > 2000)
            throw new IllegalArgumentException("tokenJsonPointer must start with '/' and contain at most 2000 characters");

        JsonPointer pointer;
        try {
            pointer = JsonPointer.compile(pointerValue);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("tokenJsonPointer is invalid");
        }

        String authentication = upper(authenticationType, "authenticationType");
        String clientAuthentication = upper(clientAuthenticationType, "clientAuthenticationType");
        if (authentication.equals("BASIC")) {
            String method = upper(basicMethod, "basicMethod");
            if (!method.equals("GET") && !method.equals("POST"))
                throw new IllegalArgumentException("basicMethod must be GET or POST");

            requireUsername(credentials, "credentials");
            if (optional(scope) != null || !clientAuthentication.equals("NONE") || optional(clientId) != null || clientCredentials != null)
                throw new IllegalArgumentException("OAuth client and scope parameters are not valid with BASIC authentication");

            return new RequestConfiguration(uri, authentication, method, null, clientAuthentication, null, timeout, pointer);
        }
        if (!authentication.equals("OAUTH2_PASSWORD"))
            throw new IllegalArgumentException("authenticationType must be BASIC or OAUTH2_PASSWORD");

        requireUsername(credentials, "credentials");
        switch (clientAuthentication) {
            case "NONE" -> {
                if (optional(clientId) != null || clientCredentials != null)
                    throw new IllegalArgumentException("clientId and clientCredentials require a client authentication type");
            }
            case "CLIENT_ID_BODY" -> {
                if (optional(clientId) == null || clientCredentials != null)
                    throw new IllegalArgumentException("CLIENT_ID_BODY requires clientId and does not accept clientCredentials");
            }
            case "BASIC" -> {
                if (optional(clientId) != null || clientCredentials == null)
                    throw new IllegalArgumentException("BASIC client authentication requires clientCredentials and does not accept clientId");
                requireUsername(clientCredentials, "clientCredentials");
            }
            default -> throw new IllegalArgumentException("clientAuthenticationType must be NONE, CLIENT_ID_BODY, or BASIC");
        }
        return new RequestConfiguration(uri, authentication, "POST", optional(scope), clientAuthentication, optional(clientId), timeout, pointer);
    }

    private HttpRequest request(RequestConfiguration configuration) {
        HttpRequest.Builder request = HttpRequest.newBuilder(configuration.uri())
                .timeout(configuration.timeout())
                .header("Accept", "application/json")
                .header("User-Agent", "Alertify/HttpAccessTokenExchange");
        if (configuration.authenticationType().equals("BASIC")) {
            request.header("Authorization", basic(credentials, false));
            return request.method(configuration.method(), HttpRequest.BodyPublishers.noBody()).build();
        }

        List<String> form = new ArrayList<>();
        form.add(field("grant_type", "password"));
        form.add(field("username", credentials.username()));
        form.add(field("password", credentials.password()));
        if (configuration.scope() != null)
            form.add(field("scope", configuration.scope()));
        if (configuration.clientAuthenticationType().equals("CLIENT_ID_BODY"))
            form.add(field("client_id", configuration.clientId()));
        if (configuration.clientAuthenticationType().equals("BASIC"))
            request.header("Authorization", basic(clientCredentials, true));

        return request.header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(String.join("&", form), StandardCharsets.UTF_8))
                .build();
    }

    private static URI uri(String value) {
        try {
            URI uri = URI.create(required(value, "tokenUrl"));
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                    || !Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException();

            return uri;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("tokenUrl must be an absolute HTTP or HTTPS URL without user-info or fragment");
        }
    }

    private static Duration timeout(Integer seconds) {
        if (seconds == null || seconds <= 0 || seconds > 300)
            throw new IllegalArgumentException("timeoutSeconds must be between 1 and 300");

        return Duration.ofSeconds(seconds);
    }

    private static void requireUsername(UsernamePasswordCredentials value, String name) {
        if (value.username() == null || value.username().isBlank())
            throw new IllegalArgumentException(name + " must include a non-empty username");

        if (value.username().contains(":"))
            throw new IllegalArgumentException(name + " username must not contain ':'");
    }

    private static String basic(UsernamePasswordCredentials value, boolean oauthClient) {
        String username = oauthClient ? encode(value.username()) : value.username();
        String password = oauthClient ? encode(value.password()) : value.password();
        String encoded = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }

    private static String field(String name, String value) {
        return encode(name) + "=" + encode(value);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean isJson(String contentType) {
        String normalized = contentType.toLowerCase(Locale.ROOT);
        int parameters = normalized.indexOf(';');
        String mediaType = (parameters < 0 ? normalized : normalized.substring(0, parameters)).trim();
        return mediaType.equals("application/json") || mediaType.startsWith("application/") && mediaType.endsWith("+json");
    }

    private static String required(String value, String name) {
        String normalized = optional(value);
        if (normalized == null)
            throw new IllegalArgumentException(name + " is required");

        return normalized;
    }

    private static String upper(String value, String name) {
        return required(value, name).toUpperCase(Locale.ROOT);
    }

    private static String optional(String value) {
        if (value == null)
            return null;

        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private record RequestConfiguration(URI uri, String authenticationType, String method, String scope, String clientAuthenticationType, String clientId, Duration timeout, JsonPointer tokenJsonPointer) {
    }

    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private Flow.Subscription subscription;
        private long size;
        private boolean failed;

        @Override
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            delegate.onSubscribe(value);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (failed)
                return;

            for (ByteBuffer buffer : buffers)
                size += buffer.remaining();

            if (size > MAX_RESPONSE_BYTES) {
                failed = true;
                subscription.cancel();
                delegate.onError(new IOException("Token response exceeds 1 MiB"));
                return;
            }
            delegate.onNext(buffers);
        }

        @Override
        public void onError(Throwable exception) {
            if (!failed)
                delegate.onError(exception);
        }

        @Override
        public void onComplete() {
            if (!failed)
                delegate.onComplete();
        }
    }
}
