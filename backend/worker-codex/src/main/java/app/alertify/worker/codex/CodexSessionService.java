package app.alertify.worker.codex;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.stereotype.Service;

import app.alertify.worker.ai.grpc.ChatRateLimitWindow;
import app.alertify.worker.ai.grpc.ChatTokenUsage;
import app.alertify.worker.ai.grpc.ChatUsageUpdate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
class CodexSessionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(CodexSessionService.class);

    static final String AUTH_ISSUER = "https://auth.openai.com";
    static final String API_RESOURCE = "https://api.openai.com/v1";
    static final String DEVICE_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    static final String DEVICE_VERIFICATION_URI = "https://auth.openai.com/codex/device";
    static final String PRIVATE_CODEX_RESPONSES_URI = "https://chatgpt.com/backend-api/codex/responses";
    static final String PRIVATE_CODEX_USAGE_URI = "https://chatgpt.com/backend-api/wham/usage";
    static final String PUBLIC_RESPONSES_URI = "https://api.openai.com/v1/responses";
    private static final String PRIVATE_CODEX_MODELS_URI = "https://chatgpt.com/backend-api/codex/models?client_version=0.0.0";
    private static final String PUBLIC_MODELS_URI = "https://api.openai.com/v1/models";
    private static final String ORIGINATOR = "codex_cli_rs";
    private static final String DYNAMIC_CLIENT_ID = "dynamic_agent_client";
    private static final String SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";
    private static final String LOGIN_MODE_DIRECT = "DIRECT";
    private static final String LOGIN_MODE_EXTENSION = "EXTENSION";
    private static final String LOGIN_MODE_DEVICE = "DEVICE";
    private static final Duration LOGIN_LIFETIME = Duration.ofMinutes(15);
    private static final Duration MODEL_CATALOG_LIFETIME = Duration.ofHours(1);
    private static final Duration USAGE_SNAPSHOT_LIFETIME = Duration.ofSeconds(30);
    private static final int MAX_INSTRUCTIONS_BYTES = 64 * 1024;
    private static final Pattern RATE_LIMIT_DURATION_PART = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)(ms|s|m|h|d)");

    private final EncryptedStateStore store;
    private final Duration refreshTokenLifetime;
    private final CodexHttpTransport http;
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    private final ReentrantLock stateLock = new ReentrantLock();
    private final ReentrantLock refreshLock = new ReentrantLock();
    private final ReentrantLock modelCatalogLock = new ReentrantLock();
    private final ReentrantLock usageSnapshotLock = new ReentrantLock();
    private final Semaphore modelRequests;
    private final Map<UUID, ProviderCall> activeProviderCalls = new ConcurrentHashMap<>();
    private volatile JwtDecoder jwtDecoder;
    private long sessionRevision;

    private volatile PersistentAiState state;
    private volatile PendingBrowserLogin browserLogin;
    private volatile PendingDeviceLogin deviceLogin;
    private volatile Map<String, CodexModelProfile> modelProfiles = Map.of();
    private volatile Instant modelCatalogExpiresAt = Instant.EPOCH;
    private volatile String modelCatalogIdentity = "";
    private volatile JsonNode usageSnapshot;
    private volatile Instant usageSnapshotExpiresAt = Instant.EPOCH;
    private volatile String usageSnapshotIdentity = "";

    @Autowired
    CodexSessionService(EncryptedStateStore store, CodexWorkerProperties properties) {
        this(store, properties, CodexHttpTransport.create());
    }

    CodexSessionService(EncryptedStateStore store, CodexWorkerProperties properties, CodexHttpTransport http) {
        this.store = store;
        this.http = http;
        refreshTokenLifetime = properties.refreshTokenLifetime();
        modelRequests = new Semaphore(properties.maxConcurrentModelRequests(), true);
        usageSnapshot = json.createObjectNode();
        if (refreshTokenLifetime == null || refreshTokenLifetime.isNegative() || refreshTokenLifetime.isZero())
            throw new IllegalStateException("The refresh token lifetime must be positive");
        if (properties.maxConcurrentModelRequests() < 1)
            throw new IllegalStateException("The maximum number of concurrent model requests must be positive");

        AiSettingsState initialSettings = new AiSettingsState(true, "CODEX", properties.defaultModel(), properties.defaultReasoningEffort(), "",
                properties.defaultRefreshPolicy(), properties.defaultReviewerModel(), properties.defaultReviewerReasoningEffort());
        validateSettings(initialSettings);
        PersistentAiState loaded = store.load();
        state = normalize(loaded, initialSettings);
        if (!Objects.equals(loaded, state))
            store.save(state);
    }

    PersistentAiState state() {
        return state;
    }

    AiSettingsState updateSettings(AiSettingsState settings) {
        validateSettings(settings);
        stateLock.lock();
        try {
            state = new PersistentAiState(settings, state.session(), state.dynamicClients(), state.hostId());
            store.save(state);
            return settings;
        } finally {
            stateLock.unlock();
        }
    }

    BrowserLoginStart beginBrowserLogin(String redirectUri, String loginMode, String adminSubject) {
        validateLoopbackRedirectUri(redirectUri);

        String normalizedMode = required(loginMode, "login_mode").toUpperCase(java.util.Locale.ROOT);
        if (!Set.of(LOGIN_MODE_DIRECT, LOGIN_MODE_EXTENSION).contains(normalizedMode))
            throw new IllegalArgumentException("Unsupported browser login mode");

        String subject = required(adminSubject, "admin_subject");

        stateLock.lock();
        try {
            requireNoSessionOrAttempt();
            DynamicClient client = state.dynamicClients().get(redirectUri);
            String verifier = randomUrlSafe(64);
            String callbackTicket = LOGIN_MODE_EXTENSION.equals(normalizedMode) ? randomUrlSafe(32) : "";
            browserLogin = new PendingBrowserLogin(redirectUri, normalizedMode, subject,
                    callbackTicket.isEmpty() ? "" : sha256UrlSafe(callbackTicket), randomUrlSafe(32), randomUrlSafe(32), verifier,
                    client == null ? DYNAMIC_CLIENT_ID : client.clientId(), client == null ? "" : firstNonBlank(client.subject()),
                    Instant.now().plus(LOGIN_LIFETIME));
            return new BrowserLoginStart(browserLogin, callbackTicket);
        } finally {
            stateLock.unlock();
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
        PendingBrowserLogin pending;
        String issuedClientId;
        stateLock.lock();
        try {
            pending = browserLogin;
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
            if (DYNAMIC_CLIENT_ID.equals(pending.clientId())) {
                issuedClientId = required(clientId, "client_id");
                if (DYNAMIC_CLIENT_ID.equals(issuedClientId))
                    throw new IllegalArgumentException("The OAuth callback did not issue a client ID");
            } else {
                if (clientId != null && !clientId.isBlank() && !pending.clientId().equals(clientId))
                    throw new IllegalArgumentException("The OAuth callback client ID did not match the selected registration");
                issuedClientId = pending.clientId();
            }
        } finally {
            stateLock.unlock();
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
        OAuthSession session = tokenSession(authMode, token, issuedClientId, pending.nonce(), true);
        if (!pending.expectedSubject().isBlank() && !pending.expectedSubject().equals(session.subject()))
            throw new IllegalArgumentException("The authorized account did not match the selected registration");

        stateLock.lock();
        try {
            if (browserLogin != pending)
                throw new SessionChangedException();

            Map<String, DynamicClient> clients = new HashMap<>(state.dynamicClients());
            clients.put(pending.redirectUri(), new DynamicClient(issuedClientId, session.subject(), session.accountId(), session.accountLabel()));
            state = new PersistentAiState(state.settings(), session, Map.copyOf(clients), state.hostId());
            store.save(state);
            browserLogin = null;
            sessionRevision++;
            invalidateCachesLocked();
        } finally {
            stateLock.unlock();
        }
    }

    PendingDeviceLogin beginDeviceLogin() {
        PendingDeviceLogin reservation = new PendingDeviceLogin("", "", Instant.now().plus(LOGIN_LIFETIME), 5, "STARTING", "");
        stateLock.lock();
        try {
            requireNoSessionOrAttempt();
            deviceLogin = reservation;
        } finally {
            stateLock.unlock();
        }

        PendingDeviceLogin pending;
        try {
            Map<String, Object> body = Map.of("client_id", DEVICE_CLIENT_ID);
            JsonNode response = postJson(AUTH_ISSUER + "/api/accounts/deviceauth/usercode", body);
            int interval = Math.max(1, response.path("interval").asInt(5));
            Instant expiresAt = Instant.now().plusSeconds(Math.min(LOGIN_LIFETIME.toSeconds(), response.path("expires_in").asLong(LOGIN_LIFETIME.toSeconds())));
            pending = new PendingDeviceLogin(text(response, "device_auth_id"), text(response, "user_code"),
                    expiresAt, interval, "PENDING", "");
        } catch (RuntimeException exception) {
            stateLock.lock();
            try {
                if (deviceLogin == reservation)
                    deviceLogin = null;
            } finally {
                stateLock.unlock();
            }
            throw exception;
        }

        stateLock.lock();
        try {
            if (deviceLogin != reservation)
                throw new SessionChangedException();

            deviceLogin = pending;
        } finally {
            stateLock.unlock();
        }
        Thread.ofVirtual().name("codex-device-login").start(() -> pollDeviceLogin(pending));
        return pending;
    }

    PendingDeviceLogin deviceLogin() {
        return deviceLogin;
    }

    void cancelLogin() {
        stateLock.lock();
        try {
            browserLogin = null;
            deviceLogin = null;
        } finally {
            stateLock.unlock();
        }
    }

    RefreshResult refresh() {
        SessionSnapshot current = sessionSnapshot();
        Instant previous = current.session().expiresAt();
        SessionSnapshot refreshed = refreshSession(current, true);
        return new RefreshResult(previous, refreshed.session().expiresAt(), refreshed.session().refreshExpiresAt(), refreshed.session().lastRefreshedAt());
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
        OAuthSession current;
        stateLock.lock();
        try {
            current = state.session();
            state = new PersistentAiState(state.settings(), null, state.dynamicClients(), state.hostId());
            store.save(state);
            browserLogin = null;
            deviceLogin = null;
            sessionRevision++;
            invalidateCachesLocked();
        } finally {
            stateLock.unlock();
        }
        activeProviderCalls.values().forEach(call -> call.cancel(CancellationReason.SESSION_CHANGED));

        if (current == null || current.refreshToken() == null || current.refreshToken().isBlank()) return true;

        try {
            if ("DEVICE".equals(current.authMode()))
                postJson(AUTH_ISSUER + "/oauth/revoke", Map.of("token", current.refreshToken(),
                        "token_type_hint", "refresh_token", "client_id", current.clientId()));
            else
                postForm(AUTH_ISSUER + "/oauth/revoke", Map.of("token", current.refreshToken(),
                        "token_type_hint", "refresh_token", "client_id", current.clientId()));
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    void testConnection() {
        SessionSnapshot snapshot = usableSessionSnapshot();
        OAuthSession session = snapshot.session();
        String uri = usesPrivateCodex(session) ? PRIVATE_CODEX_MODELS_URI : PUBLIC_MODELS_URI;
        HttpRequest.Builder request = authenticatedRequest(uri, session).timeout(Duration.ofSeconds(30)).GET();
        if (usesPrivateCodex(session))
            addPrivateCodexHeaders(request, session, "0.0.0");

        send(request.build());
        ensureCurrent(snapshot);
    }

    CodexRequestMetadata requestMetadata(String model, Long conversationId, Long turnId) {
        SessionSnapshot snapshot = usableSessionSnapshot();
        OAuthSession session = snapshot.session();
        String identity = firstNonBlank(session.subject(), session.accountId(), session.clientId());
        String catalogIdentity = (usesPrivateCodex(session) ? "PRIVATE:" : "PUBLIC:") + identity;
        CodexModelProfile profile = modelProfile(snapshot, model, catalogIdentity);

        String sessionId = conversationId == null ? UUID.randomUUID().toString()
                : deterministicId(snapshot.hostId() + ":conversation:" + conversationId);
        String threadId = sessionId;
        String windowId = deterministicId(sessionId + ":window");
        String requestTurnId = turnId == null ? UUID.randomUUID().toString()
                : deterministicId(snapshot.hostId() + ":turn:" + turnId);
        String installationId = snapshot.hostId().startsWith("urn:uuid:") ? snapshot.hostId().substring("urn:uuid:".length()) : snapshot.hostId();
        return new CodexRequestMetadata(model, profile.responsesLite(), profile.namespacedTools(), profile.clientVersion(), profile.privateCodex(), profile.contextWindow(), installationId,
                sessionId, threadId, windowId, requestTurnId, snapshot.revision());
    }

    ProviderCall codexRequest(Map<String, ?> body, CodexRequestMetadata metadata, Long conversationId, Long turnId) {
        SessionSnapshot snapshot = usableSessionSnapshot();
        if (snapshot.revision() != metadata.sessionRevision())
            throw new SessionChangedException();

        OAuthSession session = snapshot.session();
        Map<String, Object> requestBody = new LinkedHashMap<>(body);
        requestBody.put("prompt_cache_key", metadata.sessionId());
        String uri = metadata.privateCodex() ? PRIVATE_CODEX_RESPONSES_URI : PUBLIC_RESPONSES_URI;
        HttpRequest.Builder request = authenticatedRequest(uri, session).timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(requestBody)));
        if (metadata.privateCodex()) {
            requestBody.put("client_metadata", Map.of(
                    "x-codex-installation-id", metadata.installationId(),
                    "session_id", metadata.sessionId(),
                    "thread_id", metadata.threadId(),
                    "turn_id", metadata.turnId(),
                    "x-codex-window-id", metadata.windowId()));
            request = authenticatedRequest(uri, session).timeout(Duration.ofMinutes(5))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(requestBody)));
            addPrivateCodexHeaders(request, session, metadata.clientVersion());
            request.header("version", metadata.clientVersion())
                    .header("session-id", metadata.sessionId())
                    .header("thread-id", metadata.threadId())
                    .header("x-client-request-id", metadata.threadId())
                    .header("x-codex-window-id", metadata.windowId())
                    .header("x-codex-routing-hint", "model=" + metadata.model());
            if (metadata.responsesLite())
                request.header("x-openai-internal-codex-responses-lite", "true");
        }

        return new ProviderCall(request.build(), snapshot, metadata, conversationId, turnId);
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

    private SessionSnapshot usableSessionSnapshot() {
        SessionSnapshot current = sessionSnapshot();
        OAuthSession session = current.session();
        if (!"NONE".equals(current.refreshPolicy()) && session.expiresAt() != null && session.expiresAt().isBefore(Instant.now().plusSeconds(60)))
            return refreshSession(current, false);

        if (session.expiresAt() != null && session.expiresAt().isBefore(Instant.now()))
            throw new IllegalStateException("The access token expired and automatic refresh is disabled");
        return current;
    }

    private SessionSnapshot sessionSnapshot() {
        stateLock.lock();
        try {
            OAuthSession current = requireSession();
            if (current.refreshExpiresAt() != null && !current.refreshExpiresAt().isAfter(Instant.now()))
                throw new IllegalStateException("The refresh token expired; sign in again");

            return new SessionSnapshot(current, sessionRevision, state.settings().refreshPolicy(), state.hostId());
        } finally {
            stateLock.unlock();
        }
    }

    private SessionSnapshot refreshSession(SessionSnapshot expected, boolean force) {
        refreshLock.lock();
        try {
            SessionSnapshot current = sessionSnapshot();
            if (current.revision() != expected.revision())
                throw new SessionChangedException();
            if (!Objects.equals(current.session(), expected.session()))
                return current;
            if (!force && (current.session().expiresAt() == null
                    || !current.session().expiresAt().isBefore(Instant.now().plusSeconds(60))))
                return current;

            OAuthSession refreshed = refreshSession(current.session());
            stateLock.lock();
            try {
                if (sessionRevision != current.revision() || !Objects.equals(state.session(), current.session()))
                    throw new SessionChangedException();

                state = new PersistentAiState(state.settings(), refreshed, state.dynamicClients(), state.hostId());
                store.save(state);
                return new SessionSnapshot(refreshed, sessionRevision, state.settings().refreshPolicy(), state.hostId());
            } finally {
                stateLock.unlock();
            }
        } finally {
            refreshLock.unlock();
        }
    }

    private void ensureCurrent(SessionSnapshot snapshot) {
        stateLock.lock();
        try {
            if (sessionRevision != snapshot.revision() || state.session() == null)
                throw new SessionChangedException();
        } finally {
            stateLock.unlock();
        }
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
        OAuthSession refreshed = tokenSession(current.authMode(), token, current.clientId(), null, false);
        boolean refreshTokenRotated = refreshed.refreshToken() != null && !refreshed.refreshToken().isBlank();
        String refreshToken = refreshTokenRotated ? refreshed.refreshToken() : current.refreshToken();
        String idToken = refreshed.idToken() == null || refreshed.idToken().isBlank() ? current.idToken() : refreshed.idToken();
        return new OAuthSession(refreshed.authMode(), refreshed.accessToken(), refreshToken, idToken,
                refreshed.clientId(), firstNonBlank(refreshed.subject(), current.subject()),
                firstNonBlank(refreshed.accountId(), current.accountId()),
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
                OAuthSession session = tokenSession(LOGIN_MODE_DEVICE, token, DEVICE_CLIENT_ID, null, true);
                stateLock.lock();
                try {
                    if (deviceLogin != attempt)
                        return;
                    state = new PersistentAiState(state.settings(), session, state.dynamicClients(), state.hostId());
                    store.save(state);
                    deviceLogin = null;
                    sessionRevision++;
                    invalidateCachesLocked();
                    return;
                } finally {
                    stateLock.unlock();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException exception) {
                stateLock.lock();
                try {
                    if (deviceLogin == attempt)
                        deviceLogin = attempt.withStatus("ERROR", "Device authorization failed");
                } finally {
                    stateLock.unlock();
                }
                return;
            }
        }
        stateLock.lock();
        try {
            if (deviceLogin == attempt)
                deviceLogin = attempt.withStatus("EXPIRED", "Device authorization expired");
        } finally {
            stateLock.unlock();
        }
    }

    private OAuthSession tokenSession(String mode, JsonNode token, String clientId, String expectedNonce, boolean requireIdentity) {
        Instant receivedAt = Instant.now();
        String accessToken = text(token, "access_token");
        String refreshToken = token.path("refresh_token").asText("");
        String idToken = token.path("id_token").asText("");
        long expiresIn = Math.max(1, token.path("expires_in").asLong(3600));
        String subject = "";
        String accountId = "";
        String accountLabel = "";
        if (idToken.isBlank()) {
            if (requireIdentity)
                throw new IllegalArgumentException("The token response did not contain an ID token");
        } else {
            Jwt jwt = jwtDecoder().decode(idToken);
            if (expectedNonce != null && !Objects.equals(expectedNonce, jwt.getClaimAsString("nonce")))
                throw new IllegalArgumentException("The ID token nonce did not match the login attempt");
            if (!jwt.getAudience().contains(clientId))
                throw new IllegalArgumentException("The ID token audience did not match this client");
            JsonNode claims = json.valueToTree(jwt.getClaims());
            TokenIdentity identity = tokenIdentity(mode, claims, requireIdentity);
            subject = identity.subject();
            accountId = identity.accountId();

            accountLabel = firstNonBlank(jwt.getClaimAsString("email"),
                    claims.path("https://api.openai.com/profile").path("email").asText(""),
                    jwt.getClaimAsString("name"), subject, accountId);
        }
        return new OAuthSession(mode, accessToken, refreshToken, idToken, clientId,
                subject, accountId, accountLabel, receivedAt.plusSeconds(expiresIn),
                refreshToken.isBlank() ? null : receivedAt.plus(refreshTokenLifetime), null);
    }

    static TokenIdentity tokenIdentity(String mode, JsonNode claims, boolean required) {
        String subject = claims.path("sub").asText("");
        if (required && subject.isBlank())
            throw new IllegalArgumentException("The ID token did not contain a subject");

        return new TokenIdentity(subject, chatGptAccountId(claims, required && LOGIN_MODE_DEVICE.equals(mode)));
    }

    static String chatGptAccountId(JsonNode claims, boolean required) {
        String accountId = claims.path("https://api.openai.com/auth").path("chatgpt_account_id").asText("");
        if (required && accountId.isBlank())
            throw new IllegalArgumentException("The ID token did not contain a ChatGPT account ID");

        return accountId;
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
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(values))).build();
        HttpResponse<String> response = await(http.sendAsync(request));
        if (response.statusCode() == 403 || response.statusCode() == 404)
            return null;
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IllegalStateException("The authorization service returned HTTP " + response.statusCode());
        return parse(response.body());
    }

    private HttpResponse<String> send(HttpRequest request) {
        return successful(await(http.sendAsync(request)));
    }

    private HttpResponse<String> successful(HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw remoteServiceError(response);
        return response;
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime)
                throw runtime;

            throw new IllegalStateException("The remote service request failed", cause);
        }
    }

    private RemoteServiceException remoteServiceError(HttpResponse<String> response) {
        String code = "";
        String parameter = "";
        try {
            JsonNode error = parse(response.body()).path("error");
            code = safeDiagnostic(error.path("code").asText(""));
            parameter = safeDiagnostic(error.path("param").asText(""));
        } catch (RuntimeException ignored) {
            // Remote response bodies are intentionally excluded from diagnostics.
        }
        StringBuilder message = new StringBuilder("The remote service returned HTTP ").append(response.statusCode());
        if (!code.isBlank())
            message.append(" (code=").append(code).append(')');
        if (!parameter.isBlank())
            message.append(" (param=").append(parameter).append(')');
        return new RemoteServiceException(message.toString());
    }

    private static String safeDiagnostic(String value) {
        return value != null && value.matches("[A-Za-z0-9_.-]{1,64}") ? value : "";
    }

    private JsonNode parse(String value) {
        return json.readTree(value);
    }

    private HttpRequest.Builder authenticatedRequest(String uri, OAuthSession session) {
        return HttpRequest.newBuilder(URI.create(uri)).header("Authorization", "Bearer " + session.accessToken());
    }

    private void addPrivateCodexHeaders(HttpRequest.Builder request, OAuthSession session, String clientVersion) {
        request.header("originator", ORIGINATOR)
                .header("User-Agent", "codex_cli_rs/" + clientVersion + " (Alertify; Java)");
        if (session.accountId() != null && !session.accountId().isBlank())
            request.header("ChatGPT-Account-Id", session.accountId());
    }

    private JsonNode codexUsageSnapshot(SessionSnapshot snapshot, String clientVersion, Instant observedAt, ProviderCall providerCall) {
        usageSnapshotLock.lock();
        try {
            OAuthSession session = snapshot.session();
            String identity = firstNonBlank(session.subject(), session.accountId(), session.clientId());
            if (identity.equals(usageSnapshotIdentity) && observedAt.isBefore(usageSnapshotExpiresAt))
                return usageSnapshot;

            JsonNode refreshed = json.createObjectNode();
            try {
                HttpRequest.Builder request = authenticatedRequest(PRIVATE_CODEX_USAGE_URI, session).timeout(Duration.ofSeconds(30))
                        .header("Accept", "application/json").GET();
                addPrivateCodexHeaders(request, session, clientVersion);
                JsonNode response = parse(providerCall.sendCancelable(request.build()).body());
                if (response.isObject())
                    refreshed = response;
            } catch (SessionChangedException | ProviderRequestCancelledException exception) {
                throw exception;
            } catch (RemoteServiceException exception) {
                LOGGER.warn("Codex usage metadata is unavailable: {}", exception.getMessage());
            } catch (RuntimeException exception) {
                LOGGER.warn("Codex usage metadata is unavailable: {}", exception.getClass().getSimpleName());
            }

            stateLock.lock();
            try {
                if (sessionRevision != snapshot.revision() || state.session() == null)
                    throw new SessionChangedException();

                usageSnapshot = refreshed;
                usageSnapshotIdentity = identity;
                usageSnapshotExpiresAt = observedAt.plus(USAGE_SNAPSHOT_LIFETIME);
                return usageSnapshot;
            } finally {
                stateLock.unlock();
            }
        } finally {
            usageSnapshotLock.unlock();
        }
    }

    private CodexModelProfile modelProfile(SessionSnapshot snapshot, String model, String catalogIdentity) {
        modelCatalogLock.lock();
        try {
            if (Instant.now().isAfter(modelCatalogExpiresAt) || !catalogIdentity.equals(modelCatalogIdentity))
                refreshModelCatalog(snapshot, catalogIdentity);

            CodexModelProfile profile = modelProfiles.get(model);
            if (profile == null)
                throw new IllegalArgumentException("The configured model is not available in the Codex model catalog");
            return profile;
        } finally {
            modelCatalogLock.unlock();
        }
    }

    private void refreshModelCatalog(SessionSnapshot snapshot, String catalogIdentity) {
        OAuthSession session = snapshot.session();
        boolean privateCodex = usesPrivateCodex(session);
        String uri = privateCodex ? PRIVATE_CODEX_MODELS_URI : PUBLIC_MODELS_URI;
        HttpRequest.Builder request = authenticatedRequest(uri, session).timeout(Duration.ofSeconds(30)).GET();
        if (privateCodex)
            addPrivateCodexHeaders(request, session, "0.0.0");

        Map<String, CodexModelProfile> refreshed = parseModelCatalog(parse(send(request.build()).body()), privateCodex);
        if (refreshed.isEmpty())
            throw new IllegalStateException("The Codex model catalog was empty");

        stateLock.lock();
        try {
            if (sessionRevision != snapshot.revision() || state.session() == null)
                throw new SessionChangedException();

            modelProfiles = Map.copyOf(refreshed);
            modelCatalogIdentity = catalogIdentity;
            modelCatalogExpiresAt = Instant.now().plus(MODEL_CATALOG_LIFETIME);
        } finally {
            stateLock.unlock();
        }
    }

    private void invalidateCachesLocked() {
        modelProfiles = Map.of();
        modelCatalogIdentity = "";
        modelCatalogExpiresAt = Instant.EPOCH;
        usageSnapshot = json.createObjectNode();
        usageSnapshotIdentity = "";
        usageSnapshotExpiresAt = Instant.EPOCH;
    }

    static Map<String, CodexModelProfile> parseModelCatalog(JsonNode response, boolean privateCodex) {
        JsonNode models = response.path("models");
        if (!models.isArray())
            models = response.path("data");
        if (!models.isArray())
            throw new IllegalStateException("The Codex model catalog did not contain a model array");

        Map<String, CodexModelProfile> profiles = new HashMap<>();
        models.forEach(model -> {
            String slug = firstNonBlank(model.path("slug").asText(""), model.path("id").asText(""));
            if (!slug.isBlank())
                profiles.put(slug, new CodexModelProfile(privateCodex && model.path("use_responses_lite").asBoolean(false),
                        !privateCodex, privateCodex ? clientVersion(model.path("minimal_client_version").asText("")) : "0.0.0",
                        privateCodex, effectiveContextWindow(model)));
        });
        return Map.copyOf(profiles);
    }

    static ChatUsageUpdate usage(JsonNode response, HttpHeaders headers, long contextWindow, Instant observedAt) {
        return usage(response, headers, null, contextWindow, observedAt);
    }

    static ChatUsageUpdate usage(JsonNode response, HttpHeaders headers, JsonNode quota, long contextWindow, Instant observedAt) {
        JsonNode usage = response.path("usage");
        long inputTokens = nonNegative(usage.path("input_tokens").asLong(0));
        long cachedInputTokens = nonNegative(usage.path("input_tokens_details").path("cached_tokens").asLong(0));
        long cacheWriteInputTokens = nonNegative(usage.path("input_tokens_details").path("cache_write_tokens").asLong(0));
        long outputTokens = nonNegative(usage.path("output_tokens").asLong(0));
        long reasoningOutputTokens = nonNegative(usage.path("output_tokens_details").path("reasoning_tokens").asLong(0));
        long totalTokens = nonNegative(usage.path("total_tokens").asLong(0));
        if (totalTokens == 0 && (inputTokens > 0 || outputTokens > 0))
            totalTokens = Math.addExact(inputTokens, outputTokens);

        ChatTokenUsage tokens = ChatTokenUsage.newBuilder().setInputTokens(inputTokens)
                .setCachedInputTokens(cachedInputTokens).setCacheWriteInputTokens(cacheWriteInputTokens)
                .setOutputTokens(outputTokens).setReasoningOutputTokens(reasoningOutputTokens)
                .setTotalTokens(totalTokens).build();
        String planType = firstNonBlank(headers.firstValue("x-codex-plan-type").orElse(""),
                optionalText(quota, "plan_type"), optionalText(quota, "planType"));
        ChatUsageUpdate.Builder result = ChatUsageUpdate.newBuilder().setRequest(tokens)
                .setModelContextWindow(Math.max(0, contextWindow)).setObservedAtEpochSeconds(observedAt.getEpochSecond())
                .setPlanType(planType);
        addStandardRateLimit(result, headers, "requests", "Requests", "x-ratelimit-limit-requests",
                "x-ratelimit-remaining-requests", "x-ratelimit-reset-requests", observedAt);
        addStandardRateLimit(result, headers, "tokens", "Tokens", "x-ratelimit-limit-tokens",
                "x-ratelimit-remaining-tokens", "x-ratelimit-reset-tokens", observedAt);
        addStandardRateLimit(result, headers, "project-tokens", "Project tokens", "x-ratelimit-limit-project-tokens",
                "x-ratelimit-remaining-project-tokens", "x-ratelimit-reset-project-tokens", observedAt);
        if (!addCodexRateLimit(result, headers, "primary", "Primary"))
            addCodexRateLimit(result, quota, "primary", "Primary", observedAt);
        if (!addCodexRateLimit(result, headers, "secondary", "Secondary"))
            addCodexRateLimit(result, quota, "secondary", "Secondary", observedAt);
        return result.build();
    }

    private static void addStandardRateLimit(ChatUsageUpdate.Builder target, HttpHeaders headers, String key, String label,
            String limitHeader, String remainingHeader, String resetHeader, Instant observedAt) {
        Long limit = headerLong(headers, limitHeader);
        Long remaining = headerLong(headers, remainingHeader);
        if (limit == null && remaining == null)
            return;

        ChatRateLimitWindow.Builder window = ChatRateLimitWindow.newBuilder().setKey(key).setLabel(label);
        if (limit != null)
            window.setLimit(limit);
        if (remaining != null)
            window.setRemaining(remaining);
        if (limit != null && limit > 0 && remaining != null) {
            double remainingPercent = clampPercent(remaining * 100.0 / limit);
            window.setRemainingPercent(remainingPercent).setUsedPercent(100.0 - remainingPercent);
        }
        Long resetsAt = resetAt(headers.firstValue(resetHeader).orElse(""), observedAt);
        if (resetsAt != null)
            window.setResetsAtEpochSeconds(resetsAt);
        target.addRateLimits(window);
    }

    private static boolean addCodexRateLimit(ChatUsageUpdate.Builder target, HttpHeaders headers, String kind, String fallbackLabel) {
        Double usedPercent = headerDouble(headers, "x-codex-" + kind + "-used-percent");
        Long windowMinutes = headerLong(headers, "x-codex-" + kind + "-window-minutes");
        Long resetsAt = headerLong(headers, "x-codex-" + kind + "-reset-at");
        if (usedPercent == null && windowMinutes == null && resetsAt == null)
            return false;

        String configuredName = headers.firstValue("x-codex-limit-name").orElse("");
        ChatRateLimitWindow.Builder window = ChatRateLimitWindow.newBuilder()
                .setKey("codex-" + kind).setLabel(configuredName.isBlank() ? fallbackLabel : configuredName + " " + fallbackLabel);
        if (usedPercent != null) {
            double used = clampPercent(usedPercent);
            window.setUsedPercent(used).setRemainingPercent(100.0 - used);
        }
        if (windowMinutes != null)
            window.setWindowMinutes(windowMinutes);
        if (resetsAt != null)
            window.setResetsAtEpochSeconds(resetsAt);
        target.addRateLimits(window);
        return true;
    }

    private static void addCodexRateLimit(ChatUsageUpdate.Builder target, JsonNode quota, String kind, String fallbackLabel, Instant observedAt) {
        JsonNode limits = firstObject(quota, "rate_limit", "rate_limits", "rateLimits");
        JsonNode window = firstObject(limits, kind + "_window", kind);
        Double usedPercent = number(window, "used_percent", "usedPercent");
        Long windowMinutes = integer(window, "window_minutes", "windowDurationMins", "limit_window_minutes");
        if (windowMinutes == null) {
            Long windowSeconds = integer(window, "limit_window_seconds", "window_seconds");
            if (windowSeconds != null)
                windowMinutes = Math.max(1, Math.ceilDiv(windowSeconds, 60));
        }
        Long resetsAt = integer(window, "reset_at", "resets_at", "resetsAt");
        if (resetsAt == null) {
            Long resetAfterSeconds = integer(window, "reset_after_seconds", "resetAfterSeconds");
            if (resetAfterSeconds != null)
                resetsAt = observedAt.plusSeconds(resetAfterSeconds).getEpochSecond();
        }
        if (usedPercent == null && windowMinutes == null && resetsAt == null)
            return;

        String configuredName = firstNonBlank(optionalText(quota, "limit_name"), optionalText(quota, "limitName"),
                optionalText(quota, "metered_limit_name"));
        ChatRateLimitWindow.Builder result = ChatRateLimitWindow.newBuilder().setKey("codex-" + kind)
                .setLabel(configuredName.isBlank() ? fallbackLabel : configuredName + " " + fallbackLabel);
        if (usedPercent != null) {
            double used = clampPercent(usedPercent);
            result.setUsedPercent(used).setRemainingPercent(100.0 - used);
        }
        if (windowMinutes != null)
            result.setWindowMinutes(windowMinutes);
        if (resetsAt != null)
            result.setResetsAtEpochSeconds(resetsAt);
        target.addRateLimits(result);
    }

    private static JsonNode firstObject(JsonNode root, String... names) {
        if (root == null)
            return null;

        for (String name : names) {
            JsonNode candidate = root.path(name);
            if (candidate.isObject())
                return candidate;
        }
        return root.isObject() ? root : null;
    }

    private static String optionalText(JsonNode root, String name) {
        return root == null ? "" : root.path(name).asText("");
    }

    private static Double number(JsonNode root, String... names) {
        if (root == null)
            return null;

        for (String name : names) {
            String value = root.path(name).asText("").trim();
            if (value.isEmpty())
                continue;
            try {
                double parsed = Double.parseDouble(value);
                if (Double.isFinite(parsed) && parsed >= 0)
                    return parsed;
            } catch (NumberFormatException ignored) {
                // Try the next compatible protocol field.
            }
        }
        return null;
    }

    private static Long integer(JsonNode root, String... names) {
        Double value = number(root, names);
        if (value == null || value > Long.MAX_VALUE)
            return null;

        return value.longValue();
    }

    private static long effectiveContextWindow(JsonNode model) {
        long contextWindow = nonNegative(model.path("context_window").asLong(
                model.path("model_context_window").asLong(0)));
        if (contextWindow == 0)
            return 0;

        long effectivePercent = Math.max(1, Math.min(100,
                model.path("effective_context_window_percent").asLong(100)));
        return Math.multiplyExact(contextWindow, effectivePercent) / 100;
    }

    private static Long headerLong(HttpHeaders headers, String name) {
        String value = headers.firstValue(name).orElse("").trim();
        if (value.isEmpty())
            return null;
        try {
            return nonNegative(Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Double headerDouble(HttpHeaders headers, String name) {
        String value = headers.firstValue(name).orElse("").trim();
        if (value.isEmpty())
            return null;
        try {
            double parsed = Double.parseDouble(value);
            return Double.isFinite(parsed) ? Math.max(0, parsed) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Long resetAt(String value, Instant observedAt) {
        if (value == null || value.isBlank())
            return null;

        Matcher matcher = RATE_LIMIT_DURATION_PART.matcher(value.trim());
        int end = 0;
        double seconds = 0;
        while (matcher.find()) {
            if (matcher.start() != end)
                return null;
            double amount = Double.parseDouble(matcher.group(1));
            seconds += switch (matcher.group(2)) {
                case "ms" -> amount / 1000.0;
                case "s" -> amount;
                case "m" -> amount * 60;
                case "h" -> amount * 3600;
                case "d" -> amount * 86400;
                default -> 0;
            };
            end = matcher.end();
        }
        if (end != value.trim().length() || seconds < 0 || !Double.isFinite(seconds))
            return null;
        return observedAt.plusMillis((long) Math.ceil(seconds * 1000)).getEpochSecond();
    }

    private static long nonNegative(long value) {
        return Math.max(0, value);
    }

    private static double clampPercent(double value) {
        return Math.max(0, Math.min(100, value));
    }

    static boolean usesPrivateCodex(OAuthSession session) {
        return LOGIN_MODE_DEVICE.equals(session.authMode());
    }

    private static String clientVersion(String value) {
        return value != null && value.matches("[0-9]+\\.[0-9]+\\.[0-9]+") ? value : "0.0.0";
    }

    private static String deterministicId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }

    void validateSession(CodexRequestMetadata metadata) {
        stateLock.lock();
        try {
            if (sessionRevision != metadata.sessionRevision() || state.session() == null)
                throw new SessionChangedException();
        } finally {
            stateLock.unlock();
        }
    }

    final class ProviderCall {
        private final UUID id = UUID.randomUUID();
        private final HttpRequest request;
        private final SessionSnapshot snapshot;
        private final CodexRequestMetadata metadata;
        private final Long conversationId;
        private final Long turnId;
        private volatile CompletableFuture<HttpResponse<String>> responseFuture;
        private volatile Thread owner;
        private volatile CancellationReason cancellationReason;

        private ProviderCall(HttpRequest request, SessionSnapshot snapshot, CodexRequestMetadata metadata, Long conversationId, Long turnId) {
            this.request = request;
            this.snapshot = snapshot;
            this.metadata = metadata;
            this.conversationId = conversationId;
            this.turnId = turnId;
        }

        CodexResponse execute() {
            long queuedAt = System.nanoTime();
            boolean acquired = false;
            owner = Thread.currentThread();
            activeProviderCalls.put(id, this);
            LOGGER.info("event=AI_MODEL_HTTP outcome=QUEUED conversationId={} turnId={} activeRequests={} availablePermits={}", conversationId, turnId, activeProviderCalls.size(), modelRequests.availablePermits());
            try {
                throwIfCancelled();
                ensureCurrent(snapshot);
                modelRequests.acquire();
                acquired = true;
                throwIfCancelled();
                ensureCurrent(snapshot);
                LOGGER.info("event=AI_MODEL_HTTP outcome=STARTED conversationId={} turnId={} queueMs={}", conversationId, turnId, elapsedMillis(queuedAt));
                HttpResponse<String> httpResponse = sendCancelable(request);
                throwIfCancelled();
                ensureCurrent(snapshot);
                JsonNode response = parseCodexStream(json, httpResponse.body());
                Instant observedAt = Instant.now();
                JsonNode quota = metadata.privateCodex() ? codexUsageSnapshot(snapshot, metadata.clientVersion(), observedAt, this) : null;
                throwIfCancelled();
                ensureCurrent(snapshot);
                LOGGER.info("event=AI_MODEL_HTTP outcome=COMPLETED conversationId={} turnId={} durationMs={}", conversationId, turnId, elapsedMillis(queuedAt));
                return new CodexResponse(response, usage(response, httpResponse.headers(), quota, metadata.contextWindow(), observedAt));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw cancelledException(exception);
            } catch (CancellationException exception) {
                throw cancelledException(exception);
            } finally {
                responseFuture = null;
                owner = null;
                activeProviderCalls.remove(id, this);
                if (acquired)
                    modelRequests.release();
            }
        }

        synchronized void cancel(CancellationReason reason) {
            if (cancellationReason != null)
                return;

            cancellationReason = reason;
            CompletableFuture<HttpResponse<String>> currentFuture = responseFuture;
            if (currentFuture != null)
                currentFuture.cancel(true);
            Thread currentOwner = owner;
            if (currentOwner != null)
                currentOwner.interrupt();
            LOGGER.info("event=AI_MODEL_HTTP outcome=CANCEL_REQUESTED conversationId={} turnId={} reason={}", conversationId, turnId, reason);
        }

        private HttpResponse<String> sendCancelable(HttpRequest currentRequest) {
            CompletableFuture<HttpResponse<String>> currentFuture;
            synchronized (this) {
                throwIfCancelled();
                currentFuture = http.sendAsync(currentRequest);
                responseFuture = currentFuture;
            }
            try {
                return successful(await(currentFuture));
            } finally {
                synchronized (this) {
                    if (responseFuture == currentFuture)
                        responseFuture = null;
                }
            }
        }

        private void throwIfCancelled() {
            if (cancellationReason != null)
                throw cancelledException(null);
        }

        private RuntimeException cancelledException(Throwable cause) {
            if (cancellationReason == CancellationReason.SESSION_CHANGED)
                return new SessionChangedException();
            return new ProviderRequestCancelledException(cause);
        }
    }

    private static long elapsedMillis(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
    }

    record CodexModelProfile(boolean responsesLite, boolean namespacedTools, String clientVersion, boolean privateCodex, long contextWindow) {
    }

    record CodexRequestMetadata(String model, boolean responsesLite, boolean namespacedTools, String clientVersion,
            boolean privateCodex, long contextWindow, String installationId, String sessionId, String threadId, String windowId, String turnId,
            long sessionRevision) {
    }

    record CodexResponse(JsonNode body, ChatUsageUpdate usage) {
    }

    record TokenIdentity(String subject, String accountId) {
    }

    private record SessionSnapshot(OAuthSession session, long revision, String refreshPolicy, String hostId) {
    }

    enum CancellationReason {
        USER,
        SESSION_CHANGED
    }

    static final class SessionChangedException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private SessionChangedException() {
            super("The Codex session changed before the request completed");
        }
    }

    static final class ProviderRequestCancelledException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private ProviderRequestCancelledException(Throwable cause) {
            super("The Codex request was cancelled", cause);
        }
    }

    static JsonNode parseCodexStream(JsonMapper json, String value) {
        CodexStreamAccumulator accumulator = new CodexStreamAccumulator();
        StringBuilder data = new StringBuilder();
        for (String line : value.split("\\R", -1)) {
            if (line.isEmpty()) {
                acceptCodexEvent(json, data, accumulator);
                data.setLength(0);
                continue;
            }
            if (line.startsWith("data:")) {
                if (!data.isEmpty())
                    data.append('\n');
                data.append(line.substring(5).stripLeading());
            }
        }
        acceptCodexEvent(json, data, accumulator);
        if (accumulator.completed == null)
            throw new IllegalStateException("The Codex response stream ended without a completed response");

        ObjectNode completed = (ObjectNode) accumulator.completed.deepCopy();
        if (!completed.path("output").isArray() || completed.path("output").isEmpty()) {
            ArrayNode output = json.createArrayNode();
            accumulator.outputItems.forEach(item -> output.add(item.deepCopy()));
            if (output.isEmpty())
                accumulator.fallbackCalls.forEach(item -> output.add(item.deepCopy()));
            if (output.isEmpty() && !accumulator.outputText.isEmpty()) {
                ObjectNode message = output.addObject();
                message.put("type", "message");
                message.put("role", "assistant");
                message.put("status", "completed");
                message.putArray("content").addObject().put("type", "output_text").put("text", accumulator.outputText.toString());
            }
            completed.set("output", output);
        }

        return completed;
    }

    private static void acceptCodexEvent(JsonMapper json, StringBuilder data, CodexStreamAccumulator accumulator) {
        if (data.isEmpty() || "[DONE]".contentEquals(data))
            return;

        JsonNode event = json.readTree(data.toString());
        String type = event.path("type").asText("");
        if ("response.completed".equals(type)) {
            JsonNode response = event.path("response");
            if (!response.isObject())
                throw new IllegalStateException("The Codex completion event did not contain a response");

            accumulator.completed = response;
        } else if ("response.output_item.done".equals(type) && event.path("item").isObject()) {
            accumulator.outputItems.add(event.path("item"));
        } else if ("response.output_text.delta".equals(type)) {
            accumulator.outputText.append(event.path("delta").asText(""));
        } else if ("response.function_call_arguments.done".equals(type) && event.path("item").isObject()) {
            accumulator.fallbackCalls.add(event.path("item"));
        }
        if ("response.failed".equals(type) || "response.incomplete".equals(type) || "error".equals(type))
            throw new IllegalStateException("The Codex response stream reported a failure");
    }

    private static final class CodexStreamAccumulator {
        private JsonNode completed;
        private final List<JsonNode> outputItems = new ArrayList<>();
        private final List<JsonNode> fallbackCalls = new ArrayList<>();
        private final StringBuilder outputText = new StringBuilder();
    }

    static final class RemoteServiceException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private RemoteServiceException(String message) {
            super(message);
        }
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
        if (settings.reviewerModel() != null && settings.reviewerModel().length() > 128)
            throw new IllegalArgumentException("The reviewer model must not exceed 128 characters");
        if (!java.util.Set.of("low", "medium", "high", "xhigh").contains(settings.reviewerReasoningEffort()))
            throw new IllegalArgumentException("Unsupported reviewer reasoning effort");
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

    private PersistentAiState normalize(PersistentAiState loaded, AiSettingsState initialSettings) {
        AiSettingsState settings = loaded == null || loaded.settings() == null ? initialSettings : loaded.settings();
        settings = new AiSettingsState(settings.enabled(), settings.provider(), settings.model(), settings.reasoningEffort(),
                settings.agentInstructions(), settings.refreshPolicy(),
                settings.reviewerModel() == null ? initialSettings.reviewerModel() : settings.reviewerModel(),
                settings.reviewerReasoningEffort() == null ? initialSettings.reviewerReasoningEffort() : settings.reviewerReasoningEffort());
        validateSettings(settings);
        Map<String, DynamicClient> clients = loaded == null || loaded.dynamicClients() == null
                ? Map.of() : new HashMap<>(loaded.dynamicClients());
        String hostId = loaded == null || loaded.hostId() == null || loaded.hostId().isBlank()
                ? "urn:uuid:" + java.util.UUID.randomUUID() : loaded.hostId();
        OAuthSession session = normalizeSessionClaims(loaded == null ? null : loaded.session());
        if (session != null) {
            clients.replaceAll((redirectUri, client) -> client.clientId().equals(session.clientId())
                    ? new DynamicClient(client.clientId(), session.subject(), session.accountId(), session.accountLabel()) : client);
        }
        PersistentAiState normalized = new PersistentAiState(settings, session, Map.copyOf(clients), hostId);
        return normalized;
    }

    private OAuthSession normalizeSessionClaims(OAuthSession session) {
        if (session == null || session.idToken() == null || session.idToken().isBlank())
            return session;

        try {
            JsonNode claims = decodeJwtPayload(session.idToken());
            TokenIdentity identity = tokenIdentity(session.authMode(), claims, false);
            String subject = firstNonBlank(identity.subject(), session.subject());
            String accountId = firstNonBlank(identity.accountId(), session.accountId());
            if (subject.isBlank() && accountId.isBlank())
                return session;

            String accountLabel = firstNonBlank(claims.path("email").asText(""),
                    claims.path("https://api.openai.com/profile").path("email").asText(""),
                    claims.path("name").asText(""), subject, accountId);
            return new OAuthSession(session.authMode(), session.accessToken(), session.refreshToken(), session.idToken(),
                    session.clientId(), subject, accountId, accountLabel, session.expiresAt(), session.refreshExpiresAt(), session.lastRefreshedAt());
        } catch (RuntimeException ignored) {
            return session;
        }
    }

    private JsonNode decodeJwtPayload(String token) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || parts[1].isBlank())
            throw new IllegalArgumentException("Invalid JWT format");

        return json.readTree(Base64.getUrlDecoder().decode(parts[1]));
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

@FunctionalInterface
interface CodexHttpTransport {
    CompletableFuture<HttpResponse<String>> sendAsync(HttpRequest request);

    static CodexHttpTransport create() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        return request -> client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }
}
