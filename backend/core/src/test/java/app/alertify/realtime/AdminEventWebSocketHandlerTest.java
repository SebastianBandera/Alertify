package app.alertify.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import app.alertify.logging.ApplicationEventLogger;
import app.alertify.logging.RequestLogContext;

import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class AdminEventWebSocketHandlerTest {

    @Mock private ApplicationEventLogger eventLogger;
    @Mock private JwtDecoder jwtDecoder;
    @Mock private JwtAuthenticationConverter authenticationConverter;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private WebSocketSession session;

    private AdminEventWebSocketHandler handler;
    private RequestLogContext request;

    @BeforeEach
    void setUp() {
        when(session.getId()).thenReturn("session-1");
        when(session.isOpen()).thenReturn(true);
        request = new RequestLogContext(UUID.randomUUID(), "/alertify/api/admin/events", "GET", System.nanoTime());
        when(session.getAttributes()).thenReturn(Map.of(RequestLogContext.REQUEST_ATTRIBUTE, request));
        handler = new AdminEventWebSocketHandler(jwtDecoder, authenticationConverter, JsonMapper.builder().build(), applicationEventPublisher, List.of(), eventLogger);
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
        verify(eventLogger).failure(eq("WEBSOCKET_AUTHENTICATION_FAILED"), argThat(data -> "ROLE_INSUFFICIENT".equals(data.get("reason"))), org.mockito.ArgumentMatchers.isNull(), eq(request));
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

    @Test
    void logsTheInitialRequestOnlyAfterAuthenticationAndNeverOnRenewal() throws Exception {
        verify(eventLogger, never()).success(any(), any(), any(), any());
        authenticateAs("ROLE_ADMIN");
        TextMessage auth = new TextMessage("{\"type\":\"AUTH\",\"token\":\"secret-token\"}");
        handler.handleTextMessage(session, auth);
        handler.handleTextMessage(session, auth);

        ArgumentCaptor<Map<String, ?>> data = ArgumentCaptor.captor();
        ArgumentCaptor<Authentication> actor = ArgumentCaptor.forClass(Authentication.class);
        verify(eventLogger).success(eq("API_REQUEST"), data.capture(), actor.capture(), eq(request));
        assertThat(data.getValue().get("status")).isEqualTo(101);
        assertThat(data.getValue().get("path")).isEqualTo(request.path());
        assertThat(data.getValue().get("sessionId")).isEqualTo("session-1");
        assertThat(((JwtAuthenticationToken) actor.getValue()).getToken().getSubject()).isEqualTo("administrator");
    }

    @Test
    void recordsAnInvalidTokenOnceWithoutItsContents() throws Exception {
        when(jwtDecoder.decode("secret-token")).thenThrow(new JwtException("sensitive decoder diagnostics"));
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"AUTH\",\"token\":\"secret-token\"}"));
        handler.afterConnectionClosed(session, CloseStatus.POLICY_VIOLATION);

        ArgumentCaptor<Map<String, ?>> data = ArgumentCaptor.captor();
        verify(eventLogger).failure(eq("WEBSOCKET_AUTHENTICATION_FAILED"), data.capture(), org.mockito.ArgumentMatchers.isNull(), eq(request));
        assertThat(data.getValue().get("reason")).isEqualTo("TOKEN_INVALID");
        assertThat(data.getValue().get("phase")).isEqualTo("INITIAL");
        assertThat(data.getValue().toString()).doesNotContain("secret-token", "sensitive decoder diagnostics");
        verify(eventLogger, never()).success(any(), any(), any(), any());
    }

    @Test
    void recordsClosingBeforeAuthAndCannotAuthenticateThatSessionLater() throws Exception {
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"AUTH\",\"token\":\"secret-token\"}"));

        verify(eventLogger).failure(eq("WEBSOCKET_AUTHENTICATION_FAILED"), org.mockito.ArgumentMatchers.argThat(data -> "CLOSED_BEFORE_AUTH".equals(data.get("reason"))), org.mockito.ArgumentMatchers.isNull(), eq(request));
        verify(eventLogger, never()).success(any(), any(), any(), any());
    }

    @Test
    void closingAnAuthenticatedSessionDoesNotReportAnAuthenticationFailure() throws Exception {
        authenticateAs("ROLE_ADMIN");
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"AUTH\",\"token\":\"secret-token\"}"));
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);

        verify(eventLogger, never()).failure(any(), any(), any(), any());
    }


    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"type\":\"UNKNOWN\"}", "{\"type\":\"REQUEST\"}", "{\"type\":\"AUTH\"}"})
    void malformedOrUnauthenticatedFramesProduceOneFailure(String payload) throws Exception {
        handler.handleTextMessage(session, new TextMessage(payload));
        handler.afterConnectionClosed(session, CloseStatus.POLICY_VIOLATION);

        verify(eventLogger).failure(eq("WEBSOCKET_AUTHENTICATION_FAILED"), any(), org.mockito.ArgumentMatchers.isNull(), eq(request));
        verify(eventLogger, never()).success(any(), any(), any(), any());
    }

    @Test
    void rejectsExpiredDecodedTokensWithoutLoggingASuccess() throws Exception {
        Jwt jwt = Jwt.withTokenValue("secret-token").header("alg", "RS256").subject("expired-subject").expiresAt(Instant.now().minusSeconds(1)).build();
        when(jwtDecoder.decode("secret-token")).thenReturn(jwt);
        when(authenticationConverter.convert(jwt)).thenReturn(new JwtAuthenticationToken(jwt, java.util.List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"AUTH\",\"token\":\"secret-token\"}"));

        verify(eventLogger).failure(eq("WEBSOCKET_AUTHENTICATION_FAILED"), argThat(data -> "TOKEN_EXPIRED".equals(data.get("reason"))), org.mockito.ArgumentMatchers.isNull(), eq(request));
        verify(eventLogger, never()).success(any(), any(), any(), any());
    }

    private void authenticateAs(String authority) {
        Jwt jwt = Jwt.withTokenValue("secret-token")
                .header("alg", "RS256")
                .subject("administrator")
                .issuedAt(Instant.now().minusSeconds(5))
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(jwtDecoder.decode("secret-token")).thenReturn(jwt);
        when(authenticationConverter.convert(jwt)).thenReturn(new JwtAuthenticationToken(jwt, java.util.List.of(new SimpleGrantedAuthority(authority))));
    }
}
