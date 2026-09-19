package app.alertify.realtime;

/** Signals that an administrative WebSocket session completed its initial authentication. */
public record AdminSessionAuthenticatedEvent(String sessionId) {
}
