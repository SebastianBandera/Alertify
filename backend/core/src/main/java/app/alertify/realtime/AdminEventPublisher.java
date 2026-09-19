package app.alertify.realtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import tools.jackson.databind.json.JsonMapper;

/** Serializes and publishes typed events through authenticated administrative sessions. */
@Service
public class AdminEventPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminEventPublisher.class);

    private final AdminEventWebSocketHandler handler;
    private final JsonMapper jsonMapper;

    public AdminEventPublisher(AdminEventWebSocketHandler handler, JsonMapper jsonMapper) {
        this.handler = handler;
        this.jsonMapper = jsonMapper;
    }

    public boolean hasAuthenticatedSessions() {
        return handler.hasAuthenticatedSessions();
    }

    public void publish(String eventName, Object payload) {
        String message = serialize(eventName, payload);
        if (message != null)
            handler.broadcastText(message);
    }

    public void publishTo(String sessionId, String eventName, Object payload) {
        String message = serialize(eventName, payload);
        if (message != null)
            handler.sendTextTo(sessionId, message);
    }

    private String serialize(String eventName, Object payload) {
        try {
            return jsonMapper.writeValueAsString(new EventMessage("EVENT", eventName, payload));
        } catch (RuntimeException exception) {
            LOGGER.warn("Administrative event serialization failed: eventName={}", eventName, exception);
            return null;
        }
    }

    private record EventMessage(String type, String name, Object payload) { }
}
