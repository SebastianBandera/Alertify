package app.alertify.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import app.alertify.logging.ApplicationEventLogger;

class AiToolExecutorTest {

    private final ApplicationEventLogger events = mock(ApplicationEventLogger.class);
    private final AiToolExecutor executor = new AiToolExecutor(events);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsCallsWithoutAnAuthenticatedUser() {
        assertThrows(AccessDeniedException.class, () -> executor.execute("alertify_test", () -> "ignored"));
    }

    @Test
    void derivesIdentityAndConversationFromTrustedContexts() {
        var authentication = UsernamePasswordAuthenticationToken.authenticated("alice", "unused",
                java.util.List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        AtomicReference<AiInvocationContext> observed = new AtomicReference<>();

        String result = AiConversationContext.callWith(37L,
                () -> executor.execute("alertify_test", () -> {
                    observed.set(AiInvocationContextHolder.current());
                    return "done";
                }));

        assertEquals("done", result);
        assertEquals("alice", observed.get().userSubject());
        assertEquals("alice", observed.get().username());
        assertEquals(37L, observed.get().conversationId());
        assertEquals(java.util.Set.of("ROLE_ADMIN"), observed.get().authorities());
        assertNull(AiInvocationContextHolder.current());
        ArgumentCaptor<Map<String, Object>> data = mapCaptor();
        verify(events).success(eq("AI_TOOL_STARTED"), data.capture());
        assertEquals("alertify_test", data.getValue().get("toolName"));
        assertEquals(37L, data.getValue().get("conversationId"));
    }

    @Test
    void auditsOnlyTheFailureTypeAndRethrowsTheOriginalFailure() {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "alice", "unused", java.util.List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        IllegalArgumentException failure = new IllegalArgumentException("sensitive input must not be logged");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> executor.execute("alertify_test", () -> { throw failure; }));

        assertSame(failure, thrown);
        ArgumentCaptor<Map<String, Object>> data = mapCaptor();
        verify(events).failure(eq("AI_TOOL_FAILED"), data.capture());
        assertEquals(IllegalArgumentException.class.getName(), data.getValue().get("failureType"));
        assertEquals(false, data.getValue().containsValue(failure.getMessage()));
        assertNull(AiInvocationContextHolder.current());
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static ArgumentCaptor<Map<String, Object>> mapCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
    }
}
