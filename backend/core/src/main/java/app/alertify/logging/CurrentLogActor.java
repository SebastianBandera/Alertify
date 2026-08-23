package app.alertify.logging;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Extracts a stable subject and username from the current JWT authentication,
 * with explicit fallbacks for scheduled or unauthenticated system work.
 */
final class CurrentLogActor {

    private static final String SYSTEM = "system";
    private static final String UNKNOWN_AUTHENTICATED_SUBJECT = "unknown-authenticated-subject";
    private static final String UNKNOWN_AUTHENTICATED_USER = "unknown-authenticated-user";
    private static final String USERNAME_CLAIM = "preferred_username";

    private CurrentLogActor() {
    }

    static LogActor resolve() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication && authentication.isAuthenticated()) {
            String subject = jwtAuthentication.getToken().getSubject();
            String username = jwtAuthentication.getToken().getClaimAsString(USERNAME_CLAIM);

            return new LogActor(
                    firstNonBlank(
                            subject,
                            authentication.getName(),
                            UNKNOWN_AUTHENTICATED_SUBJECT
                    ),
                    firstNonBlank(
                            username,
                            authentication.getName(),
                            UNKNOWN_AUTHENTICATED_USER
                    )
            );
        }
        return new LogActor(SYSTEM, SYSTEM);
    }

    private static String firstNonBlank(String... valuesByPrecedence) {
        for (String value : valuesByPrecedence) {
            if (value != null && !value.isBlank())
                return value;
        }
        throw new IllegalArgumentException("At least one non-blank value is required");
    }
}