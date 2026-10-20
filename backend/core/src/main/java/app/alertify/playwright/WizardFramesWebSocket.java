package app.alertify.playwright;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import tools.jackson.databind.json.JsonMapper;

/** Independent, bounded image transport; authentication is in-memory in the first frame. */
@Configuration
public class WizardFramesWebSocket extends TextWebSocketHandler implements WebSocketConfigurer, AutoCloseable {
    private final ObjectProvider<PlaywrightWizardService> service;
    private final JwtDecoder decoder;
    private final JwtAuthenticationConverter converter;
    private final JsonMapper json;
    private final String origin;
    private final Map<String, Peer> peers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public WizardFramesWebSocket(ObjectProvider<PlaywrightWizardService> service, JwtDecoder decoder, JwtAuthenticationConverter converter, JsonMapper json, @Value("${security.cors.allowed-origin}") String origin) {
        this.service = service;
        this.decoder = decoder;
        this.converter = converter;
        this.json = json;
        this.origin = origin;
        scheduler.scheduleWithFixedDelay(this::frames, 200, 200, TimeUnit.MILLISECONDS);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) { registry.addHandler(this, "/api/admin/playwright-frames").setAllowedOrigins(origin); }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        session.setTextMessageSizeLimit(32768);
        peers.put(session.getId(), new Peer(new ConcurrentWebSocketSessionDecorator(session, 1000, 1024 * 1024, ConcurrentWebSocketSessionDecorator.OverflowStrategy.DROP)));
    }

    @Override
    protected void handleTextMessage(WebSocketSession socket, TextMessage message) {
        Peer peer = peers.get(socket.getId());
        try {
            var payload = json.readTree(message.getPayload());
            if (peer == null || !payload.path("type").asString().equals("AUTH"))
                throw new IllegalArgumentException("Authentication required");

            var jwt = decoder.decode(payload.path("token").asString());
            Authentication authentication = converter.convert(jwt);
            UUID id = UUID.fromString(payload.path("draftId").asString());
            service.getObject().frame(authentication, id);
            peer.authentication = authentication;
            peer.draft = id;
            peer.expires = jwt.getExpiresAt();
            peer.session.sendMessage(new TextMessage("{\"type\":\"AUTHENTICATED\"}"));
        } catch (Exception ignored) {
            disconnect(socket.getId());
        }
    }

    private void frames() {
        for (Map.Entry<String, Peer> entry : peers.entrySet()) {
            Peer peer = entry.getValue();
            try {
                if (peer.authentication == null) {
                    if (System.nanoTime() - peer.created > TimeUnit.SECONDS.toNanos(10)) disconnect(entry.getKey());

                    continue;
                }
                if (peer.expires == null || !peer.expires.isAfter(Instant.now())) {
                    disconnect(entry.getKey());
                    continue;
                }
                var frame = service.getObject().frame(peer.authentication, peer.draft);
                if (frame != null && frame.getFrameId() != peer.lastFrame) {
                    byte[] image = frame.getImage().toByteArray();
                    ByteBuffer payload = ByteBuffer.allocate(8 + image.length).putLong(frame.getFrameId()).put(image);
                    peer.session.sendMessage(new BinaryMessage(payload.array()));
                    peer.lastFrame = frame.getFrameId();
                }
            } catch (Exception ignored) { disconnect(entry.getKey()); }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { peers.remove(session.getId()); }

    private void disconnect(String id) {
        Peer peer = peers.remove(id);
        if (peer != null) {
            try { peer.session.close(CloseStatus.POLICY_VIOLATION); } catch (Exception ignored) { }
        }
    }

    @Override public void close() { peers.keySet().forEach(this::disconnect); scheduler.shutdownNow(); }

    private static final class Peer {
        private final WebSocketSession session;
        private final long created = System.nanoTime();
        private volatile Authentication authentication;
        private volatile UUID draft;
        private volatile Instant expires;
        private long lastFrame;
        private Peer(WebSocketSession session) { this.session = session; }
    }
}
