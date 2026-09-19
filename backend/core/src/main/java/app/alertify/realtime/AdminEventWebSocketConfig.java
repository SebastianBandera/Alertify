package app.alertify.realtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** Registers the general, admin-only real-time event channel. */
@Configuration
@EnableWebSocket
public class AdminEventWebSocketConfig implements WebSocketConfigurer {

    private final AdminEventWebSocketHandler handler;
    private final String allowedOrigin;

    public AdminEventWebSocketConfig(AdminEventWebSocketHandler handler, @Value("${security.cors.allowed-origin}") String allowedOrigin) {
        this.handler = handler;
        this.allowedOrigin = allowedOrigin;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/admin/events")
                .setAllowedOrigins(allowedOrigin);
    }
}
