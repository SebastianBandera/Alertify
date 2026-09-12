package app.alertify.system;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import app.alertify.system.api.SystemStatusSummaryResponse;

/**
 * Keeps an unauthenticated socket inert until it receives an AUTH frame. The
 * browser cannot attach a Bearer header to a native WebSocket handshake, so
 * the token is deliberately sent only as a WebSocket message and is never
 * logged or included in an outbound payload.
 */
@Component
public class SystemStatusTickerWebSocketHandler extends TextWebSocketHandler implements AutoCloseable {

    private static final Duration AUTH_TIMEOUT = Duration.ofSeconds(10);
    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";

    private final JwtDecoder jwtDecoder;
    private final JwtAuthenticationConverter jwtAuthenticationConverter;
    private final SystemStatusService systemStatusService;
    private final JsonMapper jsonMapper;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ConcurrentMap<String, SessionState> sessions = new ConcurrentHashMap<>();
    private volatile SystemStatusSummaryResponse lastPublishedSummary;

    public SystemStatusTickerWebSocketHandler(JwtDecoder jwtDecoder, JwtAuthenticationConverter jwtAuthenticationConverter, SystemStatusService systemStatusService, JsonMapper jsonMapper) {
        this.jwtDecoder = jwtDecoder;
        this.jwtAuthenticationConverter = jwtAuthenticationConverter;
        this.systemStatusService = systemStatusService;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        SessionState state = new SessionState(session);
        state.authTimeout = scheduler.schedule(() -> close(session), AUTH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        sessions.put(session.getId(), state);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        SessionState state = sessions.get(session.getId());
        if (state == null) {
            close(session);
            return;
        }

        try {
            JsonNode payload = jsonMapper.readTree(message.getPayload());
            JsonNode type = payload.get("type");
            JsonNode token = payload.get("token");
            if (type == null || !type.isString() || !"AUTH".equals(type.stringValue()) || token == null || !token.isString()) {
                close(session);
                return;
            }

            authenticate(state, token.stringValue());
        } catch (RuntimeException exception) {
            close(session);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        remove(session.getId());
    }

    boolean hasAuthenticatedSessions() {
        return sessions.values().stream().anyMatch(SessionState::authenticated);
    }

    void publishCurrentStatus() {
        publishCurrentStatus(true);
    }

    void publishCurrentStatusIfChanged() {
        publishCurrentStatus(false);
    }

    private synchronized void publishCurrentStatus(boolean force) {
        if (!hasAuthenticatedSessions())
            return;

        String payload;
        try {
            SystemStatusSummaryResponse summary = systemStatusService.tickerSummary();
            if (!force && summary.equals(lastPublishedSummary))
                return;

            lastPublishedSummary = summary;
            payload = jsonMapper.writeValueAsString(new StatusMessage("STATUS", summary));
        } catch (RuntimeException exception) {
            return;
        }

        for (SessionState state : sessions.values())
            if (state.authenticated())
                send(state.session, payload);
    }

    private void authenticate(SessionState state, String token) {
        Jwt jwt = jwtDecoder.decode(token);
        Authentication authentication = jwtAuthenticationConverter.convert(jwt);
        if (authentication == null || authentication.getAuthorities().stream().noneMatch(authority -> ADMIN_AUTHORITY.equals(authority.getAuthority()))) {
            close(state.session);
            return;
        }

        Instant expiresAt = jwt.getExpiresAt();
        if (expiresAt == null || !expiresAt.isAfter(Instant.now())) {
            close(state.session);
            return;
        }

        synchronized (state) {
            cancel(state.authTimeout);
            cancel(state.expiration);
            state.authenticated = true;
            state.expiration = scheduler.schedule(() -> close(state.session), Duration.between(Instant.now(), expiresAt).toMillis(), TimeUnit.MILLISECONDS);
        }
        publishTo(state.session);
    }

    private void publishTo(WebSocketSession session) {
        try {
            send(session, jsonMapper.writeValueAsString(new StatusMessage("STATUS", systemStatusService.tickerSummary())));
        } catch (RuntimeException exception) {
            close(session);
        }
    }

    private static void send(WebSocketSession session, String payload) {
        try {
            synchronized (session) {
                if (session.isOpen())
                    session.sendMessage(new TextMessage(payload));
            }
        } catch (IOException exception) {
            close(session);
        }
    }

    private static void close(WebSocketSession session) {
        try {
            if (session.isOpen())
                session.close(CloseStatus.POLICY_VIOLATION);
        } catch (IOException ignored) {
            // A failed close has the same result for this optional channel.
        }
    }

    private void remove(String sessionId) {
        SessionState state = sessions.remove(sessionId);
        if (state != null) {
            cancel(state.authTimeout);
            cancel(state.expiration);
        }
    }

    private static void cancel(ScheduledFuture<?> future) {
        if (future != null)
            future.cancel(false);
    }

    @Override
    public void close() {
        scheduler.close();
    }

    private record StatusMessage(String type, SystemStatusSummaryResponse summary) { }

    private static final class SessionState {
        private final WebSocketSession session;
        private volatile boolean authenticated;
        private ScheduledFuture<?> authTimeout;
        private ScheduledFuture<?> expiration;

        private SessionState(WebSocketSession session) {
            this.session = session;
        }

        private boolean authenticated() { return authenticated; }
    }
}
