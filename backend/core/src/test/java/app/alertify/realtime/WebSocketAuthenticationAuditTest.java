package app.alertify.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.web.socket.WebSocketSession;

import app.alertify.logging.ApplicationEventLogger;
import app.alertify.logging.RequestLogContext;

class WebSocketAuthenticationAuditTest {

    private final ApplicationEventLogger eventLogger = mock(ApplicationEventLogger.class);
    private final WebSocketSession session = mock(WebSocketSession.class);
    private final Authentication actor = mock(Authentication.class);
    private final RequestLogContext request = new RequestLogContext(UUID.randomUUID(), "/alertify/api/admin/events", "GET", System.nanoTime());
    private final AtomicLong time = new AtomicLong();
    private WebSocketAuthenticationAudit audit;

    @BeforeEach
    void setUp() {
        when(session.getId()).thenReturn("session-1");
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(Map.of(RequestLogContext.REQUEST_ATTRIBUTE, request));
        audit = new WebSocketAuthenticationAudit(eventLogger, session, Duration.ofSeconds(10), time::get);
    }

    @Test
    void elapsedDeadlineRejectsAuthEvenBeforeTheTimerRuns() {
        time.set(Duration.ofSeconds(10).toNanos());

        assertThat(audit.authenticated(actor)).isFalse();
        audit.timedOut();
        audit.disconnected();

        verify(eventLogger).failure(eq("WEBSOCKET_AUTHENTICATION_FAILED"), argThat(data -> "AUTH_TIMEOUT".equals(data.get("reason"))), isNull(), eq(request));
        verify(eventLogger, never()).success(any(), any(), any(), any());
    }

    @Test
    void aPendingTimerCannotCloseAnAuthenticatedSession() {
        assertThat(audit.authenticated(actor)).isTrue();
        assertThat(audit.timedOut()).isFalse();
        audit.disconnected();

        verify(eventLogger, never()).failure(any(), any(), any(), any());
    }

    @Test
    void failedRenewalUsesTheLastAuthenticatedIdentityAndLogsOnce() {
        assertThat(audit.authenticated(actor)).isTrue();
        audit.failed("TOKEN_INVALID");
        audit.failed("TOKEN_MISSING");
        audit.disconnected();

        assertThat(audit.authenticated(actor)).isFalse();
        verify(eventLogger).failure(eq("WEBSOCKET_AUTHENTICATION_FAILED"), argThat(data -> "RENEWAL".equals(data.get("phase")) && "TOKEN_INVALID".equals(data.get("reason"))), eq(actor), eq(request));
        verify(eventLogger).success(eq("API_REQUEST"), any(), eq(actor), eq(request));
    }

    @Test
    void aClosedSessionCannotRecordSuccess() {
        when(session.isOpen()).thenReturn(false);

        assertThat(audit.authenticated(actor)).isFalse();
        verify(eventLogger).failure(eq("WEBSOCKET_AUTHENTICATION_FAILED"), argThat(data -> "CLOSED_BEFORE_AUTH".equals(data.get("reason"))), isNull(), eq(request));
        verify(eventLogger, never()).success(any(), any(), any(), any());
    }

    @Test
    void closeAndTimeoutRacingCannotDuplicateFailureOrAllowLateAuth() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var close = executor.submit(() -> { await(start); audit.disconnected(); });
            var timeout = executor.submit(() -> { await(start); audit.timedOut(); });
            start.countDown();
            close.get(5, TimeUnit.SECONDS);
            timeout.get(5, TimeUnit.SECONDS);
        }

        assertThat(audit.authenticated(actor)).isFalse();
        verify(eventLogger).failure(eq("WEBSOCKET_AUTHENTICATION_FAILED"), any(), isNull(), eq(request));
        verify(eventLogger, never()).success(any(), any(), any(), any());
    }

    @Test
    void authenticationAfterOneSessionClosesDoesNotAffectAnotherSession() {
        WebSocketSession otherSession = mock(WebSocketSession.class);
        RequestLogContext otherRequest = new RequestLogContext(UUID.randomUUID(), "/api/viewer/events", "GET", System.nanoTime());
        when(otherSession.getAttributes()).thenReturn(Map.of(RequestLogContext.REQUEST_ATTRIBUTE, otherRequest));
        when(otherSession.getId()).thenReturn("session-2");
        when(otherSession.isOpen()).thenReturn(true);
        WebSocketAuthenticationAudit otherAudit = new WebSocketAuthenticationAudit(eventLogger, otherSession, Duration.ofSeconds(10), time::get);
        audit.disconnected();

        assertThat(otherAudit.authenticated(actor)).isTrue();
        verify(eventLogger).success(eq("API_REQUEST"), argThat(data -> "session-2".equals(data.get("sessionId"))), eq(actor), eq(otherRequest));
        verify(eventLogger, never()).success(any(), any(), any(), eq(request));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS))
                throw new AssertionError("Synchronization timed out");

        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }
}
