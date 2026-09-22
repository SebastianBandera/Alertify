package app.alertify.realtime;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import tools.jackson.databind.json.JsonMapper;

/** Serializes dashboard events for authenticated viewer sessions. */
@Service
public class ViewerEventPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(ViewerEventPublisher.class);
    private static final Set<String> ALLOWED_EVENT_NAMES = Set.of("DASHBOARD_ALERT", "DASHBOARD_ALERT_REMOVED");

    private final ViewerEventWebSocketHandler handler;
    private final JsonMapper jsonMapper;

    public ViewerEventPublisher(ViewerEventWebSocketHandler handler, JsonMapper jsonMapper) {
        this.handler = handler;
        this.jsonMapper = jsonMapper;
    }

    public boolean hasAuthenticatedSessions() {
        return handler.hasAuthenticatedSessions();
    }

    public void publish(String eventName, Object payload) {
        if (!ALLOWED_EVENT_NAMES.contains(eventName))
            throw new IllegalArgumentException("Unsupported viewer event: " + eventName);

        try {
            handler.broadcastText(jsonMapper.writeValueAsString(new EventMessage("EVENT", eventName, payload)));
        } catch (RuntimeException exception) {
            LOGGER.warn("Viewer event serialization failed: eventName={}", eventName, exception);
        }
    }

    private record EventMessage(String type, String name, Object payload) { }
}
