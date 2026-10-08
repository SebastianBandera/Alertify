package app.alertify.realtime;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

import org.springframework.security.core.Authentication;
import org.springframework.web.socket.WebSocketSession;

import app.alertify.logging.ApplicationEventLogger;
import app.alertify.logging.RequestLogContext;

/** Serializes authentication audit outcomes, including timeout/close races. */
final class WebSocketAuthenticationAudit {

    private final ApplicationEventLogger eventLogger;
    private final WebSocketSession session;
    private final RequestLogContext request;
    private final LongSupplier nanoTime;
    private final long deadline;
    private Authentication authentication;
    private boolean terminal;

    WebSocketAuthenticationAudit(ApplicationEventLogger eventLogger, WebSocketSession session, Duration timeout) {
        this(eventLogger, session, timeout, System::nanoTime);
    }

    WebSocketAuthenticationAudit(ApplicationEventLogger eventLogger, WebSocketSession session, Duration timeout, LongSupplier nanoTime) {
        this.eventLogger = eventLogger;
        this.session = session;
        this.request = (RequestLogContext) Objects.requireNonNull(session.getAttributes().get(RequestLogContext.REQUEST_ATTRIBUTE), "Missing WebSocket request log context");
        this.nanoTime = nanoTime;
        this.deadline = nanoTime.getAsLong() + timeout.toNanos();
    }

    synchronized boolean authenticated(Authentication validatedAuthentication) {
        if (terminal)
            return false;

        if (!session.isOpen()) {
            disconnected();
            return false;
        }
        if (authentication == null && nanoTime.getAsLong() - deadline >= 0) {
            failed("AUTH_TIMEOUT");
            return false;
        }

        boolean initial = authentication == null;
        authentication = Objects.requireNonNull(validatedAuthentication);
        if (initial) {
            Map<String, Object> data = request.data(101);
            data.put("sessionId", session.getId());
            eventLogger.success("API_REQUEST", data, authentication, request);
        }
        return true;
    }

    synchronized void failed(String reason) {
        if (terminal)
            return;

        terminal = true;
        Map<String, Object> data = request.data(101);
        data.put("sessionId", session.getId());
        data.put("phase", authentication == null ? "INITIAL" : "RENEWAL");
        data.put("reason", reason);
        eventLogger.failure("WEBSOCKET_AUTHENTICATION_FAILED", data, authentication, request);
    }

    synchronized void failedBeforeAuthentication(String reason) {
        if (authentication == null)
            failed(reason);

    }

    synchronized boolean timedOut() {
        if (terminal || authentication != null)
            return false;

        failed("AUTH_TIMEOUT");
        return true;
    }

    synchronized void disconnected() {
        if (authentication == null)
            failed("CLOSED_BEFORE_AUTH");

        terminal = true;
    }
}
