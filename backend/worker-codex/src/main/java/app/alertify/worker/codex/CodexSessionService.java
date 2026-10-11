package app.alertify.worker.codex;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
class CodexSessionService {

    static final String AUTH_ISSUER = "https://auth.openai.com";
    static final String API_RESOURCE = "https://api.openai.com/v1";
    static final String DEVICE_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    static final String DEVICE_VERIFICATION_URI = "https://auth.openai.com/codex/device";
    private static final String DYNAMIC_CLIENT_ID = "dynamic_agent_client";
    private static final String SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";
    private static final String LOGIN_MODE_DIRECT = "DIRECT";
    private static final String LOGIN_MODE_EXTENSION = "EXTENSION";
    private static final Duration LOGIN_LIFETIME = Duration.ofMinutes(15);
    private static final int MAX_INSTRUCTIONS_BYTES = 64 * 1024;

    private final EncryptedStateStore store;
    private final Duration refreshTokenLifetime;
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final ReentrantLock lock = new ReentrantLock();
    private volatile JwtDecoder jwtDecoder;

    private volatile PersistentAiState state;
    private volatile PendingBrowserLogin browserLogin;
    private volatile PendingDeviceLogin deviceLogin;

    CodexSessionService(EncryptedStateStore store, CodexWorkerProperties properties) {
        this.store = store;
        refreshTokenLifetime = properties.refreshTokenLifetime();
        if (refreshTokenLifetime == null || refreshTokenLifetime.isNegative() || refreshTokenLifetime.isZero())
            throw new IllegalStateException("The refresh token lifetime must be positive");

        AiSettingsState initialSettings = new AiSettingsState(true, "CODEX", properties.defaultModel(), properties.defaultReasoningEffort(), "",
                properties.defaultRefreshPolicy());
        validateSettings(initialSettings);
        state = normalize(store.load(), initialSettings);
    }

    PersistentAiState state() {
        return state;
    }

    AiSettingsState updateSettings(AiSettingsState settings) {
        validateSettings(settings);
        lock.lock();
        try {
            state = new PersistentAiState(settings, state.session(), state.dynamicClients(), state.hostId());
            store.save(state);
            return settings;
        } finally {
            lock.unlock();
        }
    }

    BrowserLoginStart beginBrowserLogin(String redirectUri, String loginMode, String adminSubject) {
        validateLoopbackRedirectUri(redirectUri);

        String normalizedMode = required(loginMode, "login_mode").toUpperCase(java.util.Locale.ROOT);
        if (!Set.of(LOGIN_MODE_DIRECT, LOGIN_MODE_EXTENSION).contains(normalizedMode))
            throw new IllegalArgumentException("Unsupported browser login mode");

        String subject = required(adminSubject, "admin_subject");

        lock.lock();
        try {
            requireNoSessionOrAttempt();
            DynamicClient client = state.dynamicClients().get(redirectUri);
            String verifier = randomUrlSafe(64);
            String callbackTicket = LOGIN_MODE_EXTENSION.equals(normalizedMode) ? randomUrlSafe(32) : "";
            browserLogin = new PendingBrowserLogin(redirectUri, normalizedMode, subject,
                    callbackTicket.isEmpty() ? "" : sha256UrlSafe(callbackTicket), randomUrlSafe(32), randomUrlSafe(32), verifier,
                    client == null ? DYNAMIC_CLIENT_ID : client.clientId(), client == null ? "" : client.accountId(),
                    Instant.now().plus(LOGIN_LIFETIME));
            return new BrowserLoginStart(browserLogin, callbackTicket);
        } finally {
            lock.unlock();
        }
    }

    String browserAuthorizationUrl(PendingBrowserLogin login) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("response_type", "code");
        parameters.put("client_id", login.clientId());
        parameters.put("redirect_uri", login.redirectUri());
        parameters.put("scope", SCOPES);
        parameters.put("resource", API_RESOURCE);
        parameters.put("code_challenge", sha256UrlSafe(login.verifier()));
        parameters.put("code_challenge_method", "S256");
        parameters.put("state", login.state());
        parameters.put("nonce", login.nonce());
        parameters.put("ext_agent_host_id", state.hostId());
        if (DYNAMIC_CLIENT_ID.equals(login.clientId()))
            parameters.put("agent_name_hint", "Alertify");
        return AUTH_ISSUER + "/api/accounts/authorize?" + form(parameters);
    }

    void completeBrowserLogin(String code, String receivedState, String scope, String clientId, String callbackTicket) {
        lock.lock();
        try {
            PendingBrowserLogin pending = browserLogin;
            if (pending == null || Instant.now().isAfter(pending.expiresAt()))
                throw new IllegalStateException("No active browser login attempt");

            if (LOGIN_MODE_EXTENSION.equals(pending.loginMode())) {
                String receivedTicketHash = callbackTicket == null || callbackTicket.isBlank() ? "" : sha256UrlSafe(callbackTicket);
                if (!constantTimeEquals(pending.callbackTicketHash(), receivedTicketHash))
                    throw new IllegalArgumentException("The extension callback ticket did not match the pending login attempt");
            } else if (callbackTicket != null && !callbackTicket.isBlank()) {
                throw new IllegalArgumentException("The direct callback does not accept an extension ticket");
            }

            if (!constantTimeEquals(pending.state(), receivedState))
                throw new IllegalArgumentException("The OAuth callback did not match the pending login attempt");
            String issuedClientId;
            if (DYNAMIC_CLIENT_ID.equals(pending.clientId())) {
                issuedClientId = required(clientId, "client_id");
                if (DYNAMIC_CLIENT_ID.equals(issuedClientId))
                    throw new IllegalArgumentException("The OAuth callback did not issue a client ID");
            } else {
                if (clientId != null && !clientId.isBlank() && !pending.clientId().equals(clientId))
                    throw new IllegalArgumentException("The OAuth callback client ID did not match the selected registration");
                issuedClientId = pending.clientId();
            }
            Map<String, String> values = new LinkedHashMap<>();
            values.put("grant_type", "authorization_code");
            values.put("code", required(code, "code"));
            values.put("redirect_uri", pending.redirectUri());
            values.put("client_id", issuedClientId);
            values.put("code_verifier", pending.verifier());
            values.put("resource", API_RESOURCE);
            JsonNode token = postForm(AUTH_ISSUER + "/api/accounts/oauth/token", values);
            requireScopes(token.path("scope").asText(scope));
            String authMode = LOGIN_MODE_EXTENSION.equals(pending.loginMode()) ? "BROWSER_EXTENSION" : "BROWSER";
            OAuthSession session = tokenSession(authMode, token, issuedClientId, pending.nonce());
            if (!pending.expectedAccountId().isBlank() && !pending.expectedAccountId().equals(session.accountId()))
                throw new IllegalArgumentException("The authorized account did not match the selected registration");
            Map<String, DynamicClient> clients = new HashMap<>(state.dynamicClients());
            clients.put(pending.redirectUri(), new DynamicClient(issuedClientId, session.accountId(), session.accountLabel()));
            state = new PersistentAiState(state.settings(), session, Map.copyOf(clients), state.hostId());
            store.save(state);
            browserLogin = null;
        } finally {
            lock.unlock();
        }
    }

    PendingDeviceLogin beginDeviceLogin() {
        lock.lock();
        try {
            requireNoSessionOrAttempt();
            Map<String, Object> body = Map.of("client_id", DEVICE_CLIENT_ID);
            JsonNode response = postJson(AUTH_ISSUER + "/api/accounts/deviceauth/usercode", body);
            int interval = Math.max(1, response.path("interval").asInt(5));
            Instant expiresAt = Instant.now().plusSeconds(Math.min(LOGIN_LIFETIME.toSeconds(), response.path("expires_in").asLong(LOGIN_LIFETIME.toSeconds())));
            PendingDeviceLogin pending = new PendingDeviceLogin(text(response, "device_auth_id"), text(response, "user_code"),
                    expiresAt, interval, "PENDING", "");
            deviceLogin = pending;
            Thread.ofVirtual().name("codex-device-login").start(() -> pollDeviceLogin(pending));
            return pending;
        } finally {
            lock.unlock();
        }
    }

    PendingDeviceLogin deviceLogin() {
        return deviceLogin;
    }

    void cancelLogin() {
        lock.lock();
        try {
            browserLogin = null;
            deviceLogin = null;
        } finally {
            lock.unlock();
        }
    }

    RefreshResult refresh() {
        lock.lock();
        try {
            OAuthSession current = requireSession();
            Instant previous = current.expiresAt();
            OAuthSession refreshed = refreshSession(current);
            state = new PersistentAiState(state.settings(), refreshed, state.dynamicClients(), state.hostId());
            store.save(state);
            return new RefreshResult(previous, refreshed.expiresAt(), refreshed.refreshExpiresAt(), refreshed.lastRefreshedAt());
        } finally {
            lock.unlock();
        }
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    void automaticRefresh() {
        OAuthSession session = state.session();
        if (session == null || !"AUTOMATIC".equals(state.settings().refreshPolicy()) || session.expiresAt() == null || session.expiresAt().isAfter(Instant.now().plus(Duration.ofMinutes(5))))
            return;

        try {
            refresh();
        } catch (RuntimeException ignored) {
            // The state endpoint exposes the expired session; no credential or remote response is logged.
        }
    }

    boolean logout() {
        lock.lock();
        try {
            OAuthSession current = state.session();
            boolean revoked = true;
            if (current != null && current.refreshToken() != null && !current.refreshToken().isBlank()) {
                try {
                    if ("DEVICE".equals(current.authMode()))
                        postJson(AUTH_ISSUER + "/oauth/revoke", Map.of("token", current.refreshToken(),
                                "token_type_hint", "refresh_token", "client_id", current.clientId()));
                    else
                        postForm(AUTH_ISSUER + "/oauth/revoke", Map.of("token", current.refreshToken(),
                                "token_type_hint", "refresh_token", "client_id", current.clientId()));
                } catch (RuntimeException ignored) {
                    revoked = false;
                }
            }
            state = new PersistentAiState(state.settings(), null, state.dynamicClients(), state.hostId());
            store.save(state);
            browserLogin = null;
            deviceLogin = null;
            return revoked;
        } finally {
            lock.unlock();
        }
    }

    void testConnection() {
        lock.lock();
        try {
            OAuthSession session = usableSession();
            String endpoint = "DEVICE".equals(session.authMode())
                    ? "https://chatgpt.com/backend-api/codex/models"
                    : API_RESOURCE + "/models";
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + session.accessToken()).GET();
            if ("DEVICE".equals(session.authMode()) && session.accountId() != null && !session.accountId().isBlank())
                request.header("ChatGPT-Account-Id", session.accountId());

            send(request.build());
        } finally {
            lock.unlock();
        }
    }

    String sessionStatus() {
        if (browserLogin != null || deviceLogin != null)
            return "AUTHORIZING";
        OAuthSession session = state.session();
        if (session == null)
            return "SIGNED_OUT";
        if (session.refreshToken() == null || session.refreshToken().isBlank())
            return "REAUTH_REQUIRED";
        if (session.refreshExpiresAt() != null && !session.refreshExpiresAt().isAfter(Instant.now()))
            return "REAUTH_REQUIRED";

        return "ACTIVE";
    }

    boolean loginPending() {
        return browserLogin != null || deviceLogin != null;
    }

    String pendingMode() {
        if (browserLogin != null)
            return "BROWSER";
        if (deviceLogin != null)
            return "DEVICE";
        return "";
    }

    private OAuthSession usableSession() {
        OAuthSession current = requireSession();
        if (current.refreshExpiresAt() != null && !current.refreshExpiresAt().isAfter(Instant.now()))
            throw new IllegalStateException("The refresh token expired; sign in again");

        String policy = state.settings().refreshPolicy();
        if (!"NONE".equals(policy) && current.expiresAt() != null && current.expiresAt().isBefore(Instant.now().plusSeconds(60))) {
            OAuthSession refreshed = refreshSession(current);
            state = new PersistentAiState(state.settings(), refreshed, state.dynamicClients(), state.hostId());
            store.save(state);
            return refreshed;
        }
        if (current.expiresAt() != null && current.expiresAt().isBefore(Instant.now()))
            throw new IllegalStateException("The access token expired and automatic refresh is disabled");
        return current;
    }

    private OAuthSession refreshSession(OAuthSession current) {
        if (current.refreshToken() == null || current.refreshToken().isBlank())
            throw new IllegalStateException("The session has no refresh token");

        JsonNode token;
        if ("DEVICE".equals(current.authMode())) {
            token = postJson(AUTH_ISSUER + "/oauth/token", Map.of("grant_type", "refresh_token",
                    "refresh_token", current.refreshToken(), "client_id", DEVICE_CLIENT_ID));
        } else {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("grant_type", "refresh_token");
            values.put("refresh_token", current.refreshToken());
            values.put("client_id", current.clientId());
            values.put("resource", API_RESOURCE);
            token = postForm(AUTH_ISSUER + "/api/accounts/oauth/token", values);
        }
        Instant refreshedAt = Instant.now();
        OAuthSession refreshed = tokenSession(current.authMode(), token, current.clientId(), null);
        boolean refreshTokenRotated = refreshed.refreshToken() != null && !refreshed.refreshToken().isBlank();
        String refreshToken = refreshTokenRotated ? refreshed.refreshToken() : current.refreshToken();
        String idToken = refreshed.idToken() == null || refreshed.idToken().isBlank() ? current.idToken() : refreshed.idToken();
        return new OAuthSession(refreshed.authMode(), refreshed.accessToken(), refreshToken, idToken,
                refreshed.clientId(), firstNonBlank(refreshed.accountId(), current.accountId()),
                firstNonBlank(refreshed.accountLabel(), current.accountLabel()), refreshed.expiresAt(),
                refreshTokenRotated ? refreshedAt.plus(refreshTokenLifetime) : current.refreshExpiresAt(), refreshedAt);
    }

    private void pollDeviceLogin(PendingDeviceLogin attempt) {
        while (deviceLogin == attempt && Instant.now().isBefore(attempt.expiresAt())) {
            try {
                Thread.sleep(Duration.ofSeconds(attempt.intervalSeconds()));
                JsonNode response = postJsonAllowPending(AUTH_ISSUER + "/api/accounts/deviceauth/token",
                        Map.of("device_auth_id", attempt.deviceAuthId(), "user_code", attempt.userCode()));
                if (response == null)
                    continue;

                text(response, "code_challenge");
                Map<String, String> exchange = new LinkedHashMap<>();
                exchange.put("grant_type", "authorization_code");
                exchange.put("code", text(response, "authorization_code"));
                exchange.put("redirect_uri", AUTH_ISSUER + "/deviceauth/callback");
                exchange.put("client_id", DEVICE_CLIENT_ID);
                exchange.put("code_verifier", text(response, "code_verifier"));
                JsonNode token = postForm(AUTH_ISSUER + "/oauth/token", exchange);
                lock.lock();
                try {
                    if (deviceLogin != attempt)
                        return;
                    OAuthSession session = tokenSession("DEVICE", token, DEVICE_CLIENT_ID, null);
                    state = new PersistentAiState(state.settings(), session, state.dynamicClients(), state.hostId());
                    store.save(state);
                    deviceLogin = null;
                    return;
                } finally {
                    lock.unlock();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException exception) {
                if (deviceLogin == attempt)
                    deviceLogin = attempt.withStatus("ERROR", "Device authorization failed");
                return;
            }
        }
        if (deviceLogin == attempt)
            deviceLogin = attempt.withStatus("EXPIRED", "Device authorization expired");
    }

    private OAuthSession tokenSession(String mode, JsonNode token, String clientId, String expectedNonce) {
        Instant receivedAt = Instant.now();
        String accessToken = text(token, "access_token");
        String refreshToken = token.path("refresh_token").asText("");
        String idToken = token.path("id_token").asText("");
        long expiresIn = Math.max(1, token.path("expires_in").asLong(3600));
        String accountId = "";
        String accountLabel = "";
        if (!idToken.isBlank()) {
            Jwt jwt = jwtDecoder().decode(idToken);
            if (expectedNonce != null && !Objects.equals(expectedNonce, jwt.getClaimAsString("nonce")))
                throw new IllegalArgumentException("The ID token nonce did not match the login attempt");
            if (!jwt.getAudience().contains(clientId))
                throw new IllegalArgumentException("The ID token audience did not match this client");
            accountId = firstNonBlank(jwt.getClaimAsString("chatgpt_account_id"), jwt.getSubject());
            accountLabel = firstNonBlank(jwt.getClaimAsString("email"), jwt.getClaimAsString("name"), accountId);
        }
        return new OAuthSession(mode, accessToken, refreshToken, idToken, clientId,
                accountId, accountLabel, receivedAt.plusSeconds(expiresIn),
                refreshToken.isBlank() ? null : receivedAt.plus(refreshTokenLifetime), null);
    }

    private JsonNode postForm(String url, Map<String, String> values) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(values))).build();
        return parse(send(request).body());
    }

    private JsonNode postJson(String url, Map<String, ?> values) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(values))).build();
        return parse(send(request).body());
    }

    private JsonNode postJsonAllowPending(String url, Map<String, ?> values) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(values))).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 403 || response.statusCode() == 404)
                return null;
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new IllegalStateException("The authorization service returned HTTP " + response.statusCode());
            return parse(response.body());
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("The authorization request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The authorization request was interrupted", exception);
        }
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new IllegalStateException("The remote service returned HTTP " + response.statusCode());
            return response;
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("The remote service request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The remote service request was interrupted", exception);
        }
    }

    private JsonNode parse(String value) {
        return json.readTree(value);
    }

    private void requireNoSessionOrAttempt() {
        if (state.session() != null)
            throw new IllegalStateException("Log out before starting another login mode");
        if (browserLogin != null || deviceLogin != null)
            throw new IllegalStateException("Another login attempt is already active");
    }

    private JwtDecoder jwtDecoder() {
        JwtDecoder current = jwtDecoder;
        if (current != null)
            return current;

        synchronized (this) {
            if (jwtDecoder == null)
                jwtDecoder = JwtDecoders.fromIssuerLocation(AUTH_ISSUER);
            return jwtDecoder;
        }
    }

    private OAuthSession requireSession() {
        if (state.session() == null)
            throw new IllegalStateException("There is no active Codex session");
        return state.session();
    }

    private static void validateSettings(AiSettingsState settings) {
        if (!"CODEX".equals(settings.provider()))
            throw new IllegalArgumentException("Only the CODEX provider is supported");
        if (settings.model() == null || settings.model().isBlank() || settings.model().length() > 128)
            throw new IllegalArgumentException("The model must contain between 1 and 128 characters");
        if (!java.util.Set.of("low", "medium", "high", "xhigh").contains(settings.reasoningEffort()))
            throw new IllegalArgumentException("Unsupported reasoning effort");
        if (!java.util.Set.of("AUTOMATIC", "ON_USE", "NONE").contains(settings.refreshPolicy()))
            throw new IllegalArgumentException("Unsupported refresh policy");
        if (settings.agentInstructions() != null && settings.agentInstructions().getBytes(StandardCharsets.UTF_8).length > MAX_INSTRUCTIONS_BYTES)
            throw new IllegalArgumentException("Agent instructions must not exceed 64 KiB");
    }

    private static void validateLoopbackRedirectUri(String value) {
        URI uri;
        try {
            uri = URI.create(required(value, "redirect_uri"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("The browser redirect URI must be an absolute 127.0.0.1 HTTP URL", exception);
        }
        if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost()) || uri.getPort() < 1
                || uri.getUserInfo() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("The browser redirect URI must be an absolute 127.0.0.1 HTTP URL");
    }

    private static PersistentAiState normalize(PersistentAiState loaded, AiSettingsState initialSettings) {
        AiSettingsState settings = loaded == null || loaded.settings() == null ? initialSettings : loaded.settings();
        Map<String, DynamicClient> clients = loaded == null || loaded.dynamicClients() == null ? Map.of() : Map.copyOf(loaded.dynamicClients());
        String hostId = loaded == null || loaded.hostId() == null || loaded.hostId().isBlank()
                ? "urn:uuid:" + java.util.UUID.randomUUID() : loaded.hostId();
        PersistentAiState normalized = new PersistentAiState(settings, loaded == null ? null : loaded.session(), clients, hostId);
        return normalized;
    }

    private static void requireScopes(String scope) {
        HashSet<String> granted = new HashSet<>(Arrays.asList((scope == null ? "" : scope).trim().split("\\s+")));
        for (String required : SCOPES.split(" "))
            if (!granted.contains(required))
                throw new IllegalArgumentException("The authorization response did not grant all required scopes");
    }

    private static String form(Map<String, String> values) {
        return values.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue())).collect(java.util.stream.Collectors.joining("&"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String randomUrlSafe(int bytes) {
        byte[] value = new byte[bytes];
        new SecureRandom().nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String sha256UrlSafe(String value) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static String text(JsonNode node, String name) {
        return required(node.path(name).asText(""), name);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values)
            if (value != null && !value.isBlank())
                return value;
        return "";
    }

    record RefreshResult(Instant previousExpiresAt, Instant expiresAt, Instant refreshExpiresAt, Instant lastRefreshedAt) {
    }
}
