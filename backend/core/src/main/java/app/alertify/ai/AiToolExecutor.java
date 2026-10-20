package app.alertify.ai;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

import app.alertify.logging.ApplicationEventLogger;

/** Executes one tool with trusted identity, correlation and sanitized audit events. */
@Service
public class AiToolExecutor {

    private static final String USERNAME_CLAIM = "preferred_username";

    private final ApplicationEventLogger eventLogger;

    public AiToolExecutor(ApplicationEventLogger eventLogger) {
        this.eventLogger = eventLogger;
    }

    public <T> T execute(String toolName, Supplier<T> action) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken)
            throw new AccessDeniedException("An authenticated user is required for AI tools");

        AiInvocationContext context = context(authentication);
        return AiInvocationContextHolder.callWith(context, () -> {
            eventLogger.success("AI_TOOL_STARTED", eventData(toolName, context, null));
            try {
                T result = action.get();
                eventLogger.success("AI_TOOL_COMPLETED", eventData(toolName, context, null));
                return result;
            } catch (RuntimeException exception) {
                eventLogger.failure("AI_TOOL_FAILED", eventData(toolName, context, exception));
                throw exception;
            }
        });
    }

    public void execute(String toolName, Runnable action) {
        execute(toolName, () -> {
            action.run();
            return null;
        });
    }

    private static AiInvocationContext context(Authentication authentication) {
        String subject = authentication.getName();
        String username = authentication.getName();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
            String tokenSubject = jwtAuthentication.getToken().getSubject();
            String tokenUsername = jwtAuthentication.getToken().getClaimAsString(USERNAME_CLAIM);
            if (tokenSubject != null && !tokenSubject.isBlank())
                subject = tokenSubject;

            if (tokenUsername != null && !tokenUsername.isBlank())
                username = tokenUsername;
        }
        Set<String> authorities = authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .collect(Collectors.toUnmodifiableSet());
        return new AiInvocationContext(subject, username, authorities, AiConversationContext.currentId(), UUID.randomUUID());
    }

    private static Map<String, Object> eventData(String toolName, AiInvocationContext context, RuntimeException failure) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("toolName", toolName);
        data.put("invocationId", context.invocationId());
        if (context.conversationId() != null)
            data.put("conversationId", context.conversationId());

        if (failure != null)
            data.put("failureType", failure.getClass().getName());

        return data;
    }
}
