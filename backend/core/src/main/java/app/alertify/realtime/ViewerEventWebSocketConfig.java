package app.alertify.realtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** Registers the dashboard-only real-time channel used by viewers. */
@Configuration
@EnableWebSocket
public class ViewerEventWebSocketConfig implements WebSocketConfigurer {

    private final ViewerEventWebSocketHandler handler;
    private final String allowedOrigin;

    public ViewerEventWebSocketConfig(ViewerEventWebSocketHandler handler, @Value("${security.cors.allowed-origin}") String allowedOrigin) {
        this.handler = handler;
        this.allowedOrigin = allowedOrigin;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/viewer/events")
                .setAllowedOrigins(allowedOrigin);
    }
}
