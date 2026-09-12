package app.alertify.system;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** Registers the optional, admin-only live status channel. */
@Configuration
@EnableWebSocket
public class SystemStatusTickerWebSocketConfig implements WebSocketConfigurer {

    private final SystemStatusTickerWebSocketHandler handler;
    private final String allowedOrigin;

    public SystemStatusTickerWebSocketConfig(SystemStatusTickerWebSocketHandler handler, @Value("${security.cors.allowed-origin}") String allowedOrigin) {
        this.handler = handler;
        this.allowedOrigin = allowedOrigin;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/system-status/ticker")
                .setAllowedOrigins(allowedOrigin);
    }
}
