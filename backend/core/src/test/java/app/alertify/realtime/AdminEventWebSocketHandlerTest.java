package app.alertify.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class AdminEventWebSocketHandlerTest {

    @Mock private JwtDecoder jwtDecoder;
    @Mock private JwtAuthenticationConverter authenticationConverter;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private WebSocketSession session;

    private AdminEventWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        when(session.getId()).thenReturn("session-1");
        when(session.isOpen()).thenReturn(true);
        handler = new AdminEventWebSocketHandler(jwtDecoder, authenticationConverter, JsonMapper.builder().build(), applicationEventPublisher, List.of());
        handler.afterConnectionEstablished(session);
    }

    @AfterEach
    void closeHandler() {
        handler.close();
    }

    @Test
    void authenticatesAnAdministratorAndPublishesTheSessionEvent() throws Exception {
        authenticateAs("ROLE_ADMIN");

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"AUTH\",\"token\":\"secret-token\"}"));

        verify(session).sendMessage(argThat(message -> message instanceof TextMessage text && text.getPayload().equals("{\"type\":\"AUTHENTICATED\"}")));
        verify(applicationEventPublisher).publishEvent(new AdminSessionAuthenticatedEvent("session-1"));
        assertThat(handler.hasAuthenticatedSessions()).isTrue();
    }

    @Test
    void acceptsTokenRenewalWithoutPublishingAnotherInitialSessionEvent() throws Exception {
        authenticateAs("ROLE_ADMIN");
        TextMessage authentication = new TextMessage("{\"type\":\"AUTH\",\"token\":\"secret-token\"}");

        handler.handleTextMessage(session, authentication);
        handler.handleTextMessage(session, authentication);

        verify(applicationEventPublisher).publishEvent(new AdminSessionAuthenticatedEvent("session-1"));
        verify(session, times(2)).sendMessage(argThat(message -> message instanceof TextMessage text && text.getPayload().contains("AUTHENTICATED")));
    }

    @Test
    void rejectsAValidTokenWithoutTheAdministratorRole() throws Exception {
        authenticateAs("ROLE_USER");

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"AUTH\",\"token\":\"secret-token\"}"));

        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verify(applicationEventPublisher, never()).publishEvent(new AdminSessionAuthenticatedEvent("session-1"));
    }

    @Test
    void returnsAnErrorForAnUnsupportedAuthenticatedRequest() throws Exception {
        authenticateAs("ROLE_ADMIN");
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"AUTH\",\"token\":\"secret-token\"}"));

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"REQUEST\",\"requestId\":\"request-1\",\"name\":\"UNKNOWN\",\"payload\":{}}"));

        verify(session).sendMessage(argThat(message -> message instanceof TextMessage text
                && text.getPayload().contains("\"requestId\":\"request-1\"")
                && text.getPayload().contains("\"code\":\"UNSUPPORTED_REQUEST\"")));
    }

    private void authenticateAs(String authority) {
        Jwt jwt = Jwt.withTokenValue("secret-token")
                .header("alg", "RS256")
                .subject("administrator")
                .issuedAt(Instant.now().minusSeconds(5))
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(jwtDecoder.decode("secret-token")).thenReturn(jwt);
        when(authenticationConverter.convert(jwt)).thenReturn(new TestingAuthenticationToken("administrator", null, authority));
    }
}
