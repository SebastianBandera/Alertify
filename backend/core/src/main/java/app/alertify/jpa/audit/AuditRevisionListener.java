package app.alertify.jpa.audit;

import org.hibernate.envers.RevisionListener;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import app.alertify.ai.AiInvocationContext;
import app.alertify.ai.AiInvocationContextHolder;
import app.alertify.ai.AiProvenance;

/**
 * Populates each Envers revision with the current authenticated actor so audit
 * history identifies who performed a change.
 */
public final class AuditRevisionListener implements RevisionListener {

    private static final String SYSTEM_USER = "system";
    private static final String USERNAME_CLAIM = "preferred_username";

    @Override
    public void newRevision(Object revisionObject) {
        AuditRevisionEntity revision = (AuditRevisionEntity) revisionObject;
        AiProvenance provenance = AiInvocationContextHolder.currentProvenance();
        revision.setAiProvenance(provenance.assisted(), provenance.conversationId());
        AiInvocationContext aiContext = AiInvocationContextHolder.current();
        if (aiContext != null) {
            revision.setUserSubject(aiContext.userSubject());
            revision.setUsername(aiContext.username());
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication instanceof JwtAuthenticationToken jwtAuthentication && authentication.isAuthenticated()) {
            String subject = jwtAuthentication.getToken().getSubject();
            String username = jwtAuthentication.getToken().getClaimAsString(USERNAME_CLAIM);

            revision.setUserSubject(firstNonBlank(subject, authentication.getName(), SYSTEM_USER));
            revision.setUsername(firstNonBlank(username, authentication.getName(), SYSTEM_USER));
            return;
        }

        revision.setUserSubject(SYSTEM_USER);
        revision.setUsername(SYSTEM_USER);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank())
                return value;
        }
        return SYSTEM_USER;
    }
}
