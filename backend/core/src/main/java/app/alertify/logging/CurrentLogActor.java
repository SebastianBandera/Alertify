package app.alertify.logging;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

final class CurrentLogActor {

    private static final String SYSTEM = "system";
    private static final String UNKNOWN_AUTHENTICATED_SUBJECT = "unknown-authenticated-subject";
    private static final String UNKNOWN_AUTHENTICATED_USER = "unknown-authenticated-user";
    private static final String USERNAME_CLAIM = "preferred_username";

    private CurrentLogActor() {
    }

    static LogActor resolve() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuthentication
                && authentication.isAuthenticated()) {
            String subject = jwtAuthentication.getToken().getSubject();
            String username = jwtAuthentication.getToken().getClaimAsString(USERNAME_CLAIM);
            return new LogActor(
                    firstNonBlankOr(
                            UNKNOWN_AUTHENTICATED_SUBJECT,
                            subject,
                            authentication.getName()
                    ),
                    firstNonBlankOr(
                            UNKNOWN_AUTHENTICATED_USER,
                            username,
                            authentication.getName()
                    )
            );
        }
        return new LogActor(SYSTEM, SYSTEM);
    }

    private static String firstNonBlankOr(String fallback, String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank())
                return value;
        }
        return fallback;
    }
}
