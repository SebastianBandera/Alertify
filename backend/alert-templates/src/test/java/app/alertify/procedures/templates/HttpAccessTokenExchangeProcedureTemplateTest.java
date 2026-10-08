package app.alertify.procedures.templates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.sun.net.httpserver.HttpServer;

import app.alertify.procedures.ProcedureExecutionContext;
import app.alertify.procedures.template.annotation.ProcedureTemplate;
import app.alertify.worker.contract.UsernamePasswordCredentials;

class HttpAccessTokenExchangeProcedureTemplateTest {
    private static final UsernamePasswordCredentials USER = new UsernamePasswordCredentials("test-user", "dummy:p&ss");
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> response = new AtomicReference<>("{\"access_token\":\"fake.jwt.token\"}");
    private final AtomicReference<String> contentType = new AtomicReference<>("application/json; charset=utf-8");
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger responseDelayMillis = new AtomicInteger();
    private HttpServer server;
    private int status = 200;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            calls.incrementAndGet();
            method.set(exchange.getRequestMethod());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Location", url("/redirect"));
            if (contentType.get() != null)
                exchange.getResponseHeaders().set("Content-Type", contentType.get());

            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            if (responseDelayMillis.get() > 0) {
                try {
                    Thread.sleep(responseDelayMillis.get());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            } catch (java.io.IOException ignored) {
                // Expected when a timeout or response-size limit cancels the request.
            }
        });
        server.createContext("/redirect", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    @ParameterizedTest
    @ValueSource(strings = { "GET", "POST" })
    void basicSendsCredentialsWithoutBodyAndReturnsOnlyAccessToken(String requestMethod) throws Exception {
        var result = template("BASIC", requestMethod, "NONE", null, null, null, "/access_token", 3).execute(context());

        assertThat(method.get()).isEqualTo(requestMethod);
        assertThat(body.get()).isEmpty();
        assertThat(authorization.get()).isEqualTo("Basic " + Base64.getEncoder().encodeToString((USER.username() + ":" + USER.password()).getBytes(StandardCharsets.UTF_8)));
        assertThat(result.size()).isEqualTo(1);
        assertThat(result.path("accessToken").stringValue()).isEqualTo("fake.jwt.token");
        assertThat(HttpAccessTokenExchangeProcedureTemplate.class.getAnnotation(ProcedureTemplate.class).sensitiveResult()).isTrue();
    }

    @Test
    void oauthPublicClientEncodesUsernamePasswordScopeAndClientId() throws Exception {
        template("OAUTH2_PASSWORD", "ignored", "CLIENT_ID_BODY", "test client", null, "openid profile", "/access_token", 3).execute(context());

        assertThat(method.get()).isEqualTo("POST");
        assertThat(authorization.get()).isNull();
        assertThat(form()).containsEntry("grant_type", "password").containsEntry("username", USER.username())
                .containsEntry("password", USER.password()).containsEntry("client_id", "test client").containsEntry("scope", "openid profile");
    }

    @Test
    void oauthConfidentialClientUsesFormEncodedBasicCredentials() throws Exception {
        template("OAUTH2_PASSWORD", "POST", "BASIC", null, new UsernamePasswordCredentials("client +", "secret &"), null, "/access_token", 3).execute(context());

        assertThat(new String(Base64.getDecoder().decode(authorization.get().substring(6)), StandardCharsets.UTF_8)).isEqualTo("client+%2B:secret+%26");
        assertThat(form()).doesNotContainKey("client_id").containsEntry("username", USER.username());
    }

    @Test
    void acceptsApplicationJsonSubtypeAndNestedOpaqueToken() throws Exception {
        contentType.set("application/problem+json; charset=UTF-8");
        response.set("{\"data\":{\"token\":\"opaque\\\"\\\\token\"},\"ignored\":true}");

        var result = template("OAUTH2_PASSWORD", "POST", "NONE", null, null, null, "/data/token", 3).execute(context());

        assertThat(result.path("accessToken").stringValue()).isEqualTo("opaque\"\\token");
    }

    @Test
    void rejectsContradictoryParametersBeforeAnyRequest() {
        assertThatThrownBy(() -> template("OAUTH2_PASSWORD", "POST", "NONE", "client", null, null, "/access_token", 3).execute(context())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> template("OAUTH2_PASSWORD", "POST", "CLIENT_ID_BODY", null, null, null, "/access_token", 3).execute(context())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> template("OAUTH2_PASSWORD", "POST", "BASIC", "client", USER, null, "/access_token", 3).execute(context())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> template("BASIC", "POST", "NONE", null, null, "openid", "/access_token", 3).execute(context())).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = { "{}", "{\"access_token\":null}", "{\"access_token\":123}", "{\"access_token\":\"  \"}", "{\"access_token\":\"x\\r\\ny\"}" })
    void rejectsMissingNontextualBlankAndControlCharacterTokens(String value) {
        response.set(value);

        assertThatThrownBy(() -> template("OAUTH2_PASSWORD", "POST", "NONE", null, null, null, "/access_token", 3).execute(context()))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = { 302, 401, 500 })
    void neverFollowsRedirectsOrLeaksRemoteBodies(int code) {
        status = code;
        response.set("{\"access_token\":\"do-not-log-this\"}");

        assertThatThrownBy(() -> template("OAUTH2_PASSWORD", "POST", "NONE", null, null, null, "/access_token", 3).execute(context()))
                .hasMessage("Token endpoint returned HTTP " + code).hasMessageNotContaining("do-not-log-this").hasNoCause();
        assertThat(calls.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = { "text/plain", "text/problem+json", "" })
    void rejectsResponsesWithoutAnApplicationJsonContentType(String value) {
        contentType.set(value.isEmpty() ? null : value);

        assertThatThrownBy(() -> template("OAUTH2_PASSWORD", "POST", "NONE", null, null, null, "/access_token", 3).execute(context()))
                .hasMessage("Token endpoint did not return JSON").hasNoCause();
    }

    @Test
    void boundsResponseAndDoesNotExposeInvalidJson() {
        response.set("x".repeat(1024 * 1024 + 1));
        assertThatThrownBy(() -> template("OAUTH2_PASSWORD", "POST", "NONE", null, null, null, "/access_token", 3).execute(context()))
                .hasMessage("Token endpoint request failed").hasNoCause();

        response.set("private-invalid-json");
        assertThatThrownBy(() -> template("OAUTH2_PASSWORD", "POST", "NONE", null, null, null, "/access_token", 3).execute(context()))
                .hasMessage("Token endpoint returned invalid JSON").hasMessageNotContaining("private-invalid-json").hasNoCause();
    }

    @Test
    void appliesTimeoutWhileReceivingTheResponseBody() {
        responseDelayMillis.set(1500);

        assertThatThrownBy(() -> template("OAUTH2_PASSWORD", "POST", "NONE", null, null, null, "/access_token", 1).execute(context()))
                .hasMessage("Token endpoint request failed").hasNoCause();
    }

    @Test
    void validatesEndpointPointerTimeoutAndUsernamesBeforeAnyRequest() {
        assertThatThrownBy(() -> template("OAUTH2_PASSWORD", "POST", "NONE", null, null, null, "access_token", 3).execute(context())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> template("OAUTH2_PASSWORD", "POST", "NONE", null, null, null, "/access_token", 301).execute(context())).isInstanceOf(IllegalArgumentException.class);

        var userInfo = new HttpAccessTokenExchangeProcedureTemplate(USER, "http://username:private@localhost/token", "BASIC", "POST", null, "NONE", null, null, "/access_token", 10);
        assertThatThrownBy(() -> userInfo.execute(context())).hasMessageContaining("without user-info or fragment").hasNoCause();

        var fragment = new HttpAccessTokenExchangeProcedureTemplate(USER, url("/token") + "#private", "BASIC", "POST", null, "NONE", null, null, "/access_token", 10);
        assertThatThrownBy(() -> fragment.execute(context())).hasMessageContaining("without user-info or fragment").hasNoCause();

        var colon = new HttpAccessTokenExchangeProcedureTemplate(new UsernamePasswordCredentials("bad:user", "pass"), url("/token"), "BASIC", "POST", null, "NONE", null, null, "/access_token", 10);
        assertThatThrownBy(() -> colon.execute(context())).hasMessageContaining("must not contain ':'").hasNoCause();
        assertThat(calls.get()).isZero();
    }

    private HttpAccessTokenExchangeProcedureTemplate template(String type, String basicMethod, String clientType, String clientId, UsernamePasswordCredentials clientCredentials, String scope, String pointer, int timeoutSeconds) {
        return new HttpAccessTokenExchangeProcedureTemplate(USER, url("/token"), type, basicMethod, scope, clientType, clientId, clientCredentials, pointer, timeoutSeconds);
    }

    private ProcedureExecutionContext context() { return new ProcedureExecutionContext(Instant.EPOCH, Map.of()); }

    private String url(String path) { return "http://127.0.0.1:" + server.getAddress().getPort() + path; }

    private Map<String, String> form() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String pair : body.get().split("&")) {
            String[] parts = pair.split("=", 2);
            result.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return result;
    }
}
