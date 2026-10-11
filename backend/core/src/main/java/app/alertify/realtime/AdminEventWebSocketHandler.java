package app.alertify.realtime;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import app.alertify.logging.ApplicationEventLogger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Authenticated, administrative WebSocket transport. Browsers cannot attach a
 * Bearer header to a native WebSocket handshake, so the JWT is accepted only
 * in the first AUTH frame and is never logged or returned to the client.
 */
@Component
public class AdminEventWebSocketHandler extends TextWebSocketHandler implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminEventWebSocketHandler.class);
    private static final Duration AUTH_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(25);
    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";

    private final ApplicationEventLogger eventLogger;
    private final JwtDecoder jwtDecoder;
    private final JwtAuthenticationConverter jwtAuthenticationConverter;
    private final JsonMapper jsonMapper;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final Map<String, AdminRequestHandler> requestHandlers;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ConcurrentMap<String, SessionState> sessions = new ConcurrentHashMap<>();

    public AdminEventWebSocketHandler(JwtDecoder jwtDecoder, JwtAuthenticationConverter jwtAuthenticationConverter, JsonMapper jsonMapper, ApplicationEventPublisher applicationEventPublisher, List<AdminRequestHandler> requestHandlers, ApplicationEventLogger eventLogger) {
        this.eventLogger = eventLogger;
        this.jwtDecoder = jwtDecoder;
        this.jwtAuthenticationConverter = jwtAuthenticationConverter;
        this.jsonMapper = jsonMapper;
        this.applicationEventPublisher = applicationEventPublisher;
        this.requestHandlers = requestHandlers.stream().collect(Collectors.toUnmodifiableMap(AdminRequestHandler::requestName, Function.identity()));
        scheduler.scheduleWithFixedDelay(this::sendHeartbeats, HEARTBEAT_INTERVAL.toMillis(), HEARTBEAT_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        SessionState state = new SessionState(session, new WebSocketAuthenticationAudit(eventLogger, session, AUTH_TIMEOUT));
        sessions.put(session.getId(), state);
        synchronized (state.lifecycleLock) {
            state.authTimeout = scheduler.schedule(() -> {
                synchronized (state.lifecycleLock) {
                    if (state.audit.timedOut()) {
                        state.authenticated = false;
                        close(session);
                    }
                }
            }, AUTH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        SessionState state = sessions.get(session.getId());
        if (state == null) {
            close(session);
            return;
        }

        try {
            JsonNode frame = jsonMapper.readTree(message.getPayload());
            JsonNode typeNode = frame.get("type");
            if (typeNode == null || !typeNode.isString()) {
                state.audit.failedBeforeAuthentication("PROTOCOL_INVALID");
                close(session);
                return;
            }

            switch (typeNode.stringValue()) {
                case "AUTH" -> authenticate(state, frame);
                case "REQUEST" -> handleRequest(state, frame);
                default -> {
                    state.audit.failedBeforeAuthentication("PROTOCOL_INVALID");
                    close(session);
                }
            }
        } catch (RuntimeException exception) {
            state.audit.failedBeforeAuthentication("PROTOCOL_INVALID");
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

    void sendTextTo(String sessionId, String payload) {
        SessionState state = sessions.get(sessionId);
        if (state != null && state.authenticated())
            send(state, new TextMessage(payload));
    }

    void broadcastText(String payload) {
        for (SessionState state : sessions.values())
            if (state.authenticated())
                send(state, new TextMessage(payload));
    }

    private void authenticate(SessionState state, JsonNode frame) {
        JsonNode tokenNode = frame.get("token");
        if (tokenNode == null || !tokenNode.isString()) {
            rejectAuthentication(state, "TOKEN_MISSING");
            return;
        }

        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(tokenNode.stringValue());
        } catch (JwtException exception) {
            rejectAuthentication(state, "TOKEN_INVALID");
            return;
        }
        Authentication authentication = jwtAuthenticationConverter.convert(jwt);
        if (authentication == null || authentication.getAuthorities().stream().noneMatch(authority -> ADMIN_AUTHORITY.equals(authority.getAuthority()))) {
            rejectAuthentication(state, "ROLE_INSUFFICIENT");
            return;
        }

        Instant expiresAt = jwt.getExpiresAt();
        if (expiresAt == null || !expiresAt.isAfter(Instant.now())) {
            rejectAuthentication(state, "TOKEN_EXPIRED");
            return;
        }

        boolean initiallyAuthenticated;
        synchronized (state.lifecycleLock) {
            if (!state.audit.authenticated(authentication)) {
                state.authenticated = false;
                close(state.session);
                return;
            }
            initiallyAuthenticated = !state.authenticated;
            cancel(state.authTimeout);
            cancel(state.expiration);
            state.authentication = authentication;
            state.authenticated = true;
            state.expiration = scheduler.schedule(() -> {
                synchronized (state.lifecycleLock) {
                    if (state.authentication != authentication)
                        return;

                    state.audit.disconnected();
                    state.authenticated = false;
                    close(state.session);
                }
            }, Math.max(0, Duration.between(Instant.now(), expiresAt).toMillis()), TimeUnit.MILLISECONDS);
        }
        sendJson(state, new AuthenticatedMessage("AUTHENTICATED"));
        if (initiallyAuthenticated)
            applicationEventPublisher.publishEvent(new AdminSessionAuthenticatedEvent(state.session.getId()));
    }

    private static void rejectAuthentication(SessionState state, String reason) {
        synchronized (state.lifecycleLock) {
            state.audit.failed(reason);
            state.authenticated = false;
            close(state.session);
        }
    }

    private void handleRequest(SessionState state, JsonNode frame) {
        if (!state.authenticated()) {
            state.audit.failedBeforeAuthentication("AUTH_REQUIRED");
            close(state.session);
            return;
        }

        JsonNode requestIdNode = frame.get("requestId");
        JsonNode nameNode = frame.get("name");
        if (requestIdNode == null || !requestIdNode.isString() || requestIdNode.stringValue().isBlank() || nameNode == null || !nameNode.isString() || nameNode.stringValue().isBlank()) {
            close(state.session);
            return;
        }

        String requestId = requestIdNode.stringValue();
        AdminRequestHandler requestHandler = requestHandlers.get(nameNode.stringValue());
        if (requestHandler == null) {
            sendJson(state, new ErrorMessage("ERROR", requestId, "UNSUPPORTED_REQUEST", "Unsupported administrative request."));
            return;
        }

        try {
            JsonNode response = requestHandler.handle(state.authentication, frame.get("payload"));
            sendJson(state, new ResponseMessage("RESPONSE", requestId, response));
        } catch (RuntimeException exception) {
            LOGGER.warn("Administrative WebSocket request failed: requestName={}", nameNode.stringValue(), exception);
            sendJson(state, new ErrorMessage("ERROR", requestId, "REQUEST_FAILED", "Administrative request failed."));
        }
    }

    private void sendHeartbeats() {
        for (SessionState state : sessions.values())
            if (state.authenticated())
                send(state, new PingMessage());
    }

    private void sendJson(SessionState state, Object message) {
        try {
            send(state, new TextMessage(jsonMapper.writeValueAsString(message)));
        } catch (RuntimeException exception) {
            LOGGER.warn("Administrative WebSocket message serialization failed", exception);
            close(state.session);
        }
    }

    private static void send(SessionState state, WebSocketMessage<?> message) {
        try {
            synchronized (state.sendLock) {
                if (state.session.isOpen())
                    state.session.sendMessage(message);
            }
        } catch (IOException | RuntimeException exception) {
            close(state.session);
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
            synchronized (state.lifecycleLock) {
                state.audit.disconnected();
                state.authenticated = false;
                cancel(state.authTimeout);
                cancel(state.expiration);
            }
        }
    }

    private static void cancel(ScheduledFuture<?> future) {
        if (future != null)
            future.cancel(false);
    }

    @Override
    public void close() {
        sessions.values().forEach(state -> {
            synchronized (state.lifecycleLock) {
                state.audit.disconnected();
                state.authenticated = false;
                cancel(state.authTimeout);
                cancel(state.expiration);
            }
        });
        sessions.clear();
        scheduler.shutdownNow();
    }

    private record AuthenticatedMessage(String type) { }

    private record ResponseMessage(String type, String requestId, JsonNode payload) { }

    private record ErrorMessage(String type, String requestId, String code, String message) { }

    private static final class SessionState {
        private final Object lifecycleLock = new Object();
        private final Object sendLock = new Object();
        private final WebSocketSession session;
        private final WebSocketAuthenticationAudit audit;
        private volatile boolean authenticated;
        private Authentication authentication;
        private ScheduledFuture<?> authTimeout;
        private ScheduledFuture<?> expiration;

        private SessionState(WebSocketSession session, WebSocketAuthenticationAudit audit) {
            this.session = session;
            this.audit = audit;
        }

        private boolean authenticated() { return authenticated; }
    }
}
