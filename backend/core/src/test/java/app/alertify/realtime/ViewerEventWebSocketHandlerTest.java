package app.alertify.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import app.alertify.dashboard.DashboardPageRequestHandler;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class ViewerEventWebSocketHandlerTest {

    @Mock private JwtDecoder jwtDecoder;
    @Mock private JwtAuthenticationConverter authenticationConverter;
    @Mock private DashboardPageRequestHandler pageRequestHandler;
    @Mock private WebSocketSession session;

    private ViewerEventWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        when(session.getId()).thenReturn("session-1");
        when(session.isOpen()).thenReturn(true);
        handler = new ViewerEventWebSocketHandler(jwtDecoder, authenticationConverter, JsonMapper.builder().build(), pageRequestHandler);
        handler.afterConnectionEstablished(session);
    }

    @AfterEach
    void closeHandler() {
        handler.close();
    }

    @Test
    void authenticatesAViewerWithTheDashboardRole() throws Exception {
        authenticateAs("ROLE_DASHBOARD");

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"AUTH\",\"token\":\"secret-token\"}"));

        verify(session).sendMessage(argThat(message -> message instanceof TextMessage text && text.getPayload().equals("{\"type\":\"AUTHENTICATED\"}")));
        assertThat(handler.hasAuthenticatedSessions()).isTrue();
    }

    @Test
    void rejectsAValidTokenWithoutTheDashboardRole() throws Exception {
        authenticateAs("ROLE_USER");

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"AUTH\",\"token\":\"secret-token\"}"));

        verify(session).close(CloseStatus.POLICY_VIOLATION);
        assertThat(handler.hasAuthenticatedSessions()).isFalse();
    }

    @Test
    void handlesOnlyTheDashboardPageRequest() throws Exception {
        authenticateAs("ROLE_DASHBOARD");
        when(pageRequestHandler.requestName()).thenReturn("DASHBOARD_PAGE");
        when(pageRequestHandler.handleForViewer(org.mockito.ArgumentMatchers.any())).thenReturn(JsonMapper.builder().build().readTree("{\"content\":[]}"));
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"AUTH\",\"token\":\"secret-token\"}"));

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"REQUEST\",\"requestId\":\"request-1\",\"name\":\"DASHBOARD_PAGE\",\"payload\":{}}"));

        verify(session).sendMessage(argThat(message -> message instanceof TextMessage text
                && text.getPayload().contains("\"requestId\":\"request-1\"")
                && text.getPayload().contains("\"content\":[]")));
    }

    @Test
    void rejectsAdministrativeRequestsWithoutInvokingAHandler() throws Exception {
        authenticateAs("ROLE_DASHBOARD");
        when(pageRequestHandler.requestName()).thenReturn("DASHBOARD_PAGE");
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"AUTH\",\"token\":\"secret-token\"}"));

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"REQUEST\",\"requestId\":\"request-1\",\"name\":\"SYSTEM_STATUS\",\"payload\":{}}"));

        verify(session).sendMessage(argThat(message -> message instanceof TextMessage text
                && text.getPayload().contains("\"code\":\"UNSUPPORTED_REQUEST\"")));
        verify(pageRequestHandler, never()).handle(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(pageRequestHandler, never()).handleForViewer(org.mockito.ArgumentMatchers.any());
    }

    private void authenticateAs(String authority) {
        Jwt jwt = Jwt.withTokenValue("secret-token")
                .header("alg", "RS256")
                .subject("viewer")
                .issuedAt(Instant.now().minusSeconds(5))
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(jwtDecoder.decode("secret-token")).thenReturn(jwt);
        when(authenticationConverter.convert(jwt)).thenReturn(new TestingAuthenticationToken("viewer", null, authority));
    }
}
