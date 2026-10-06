package app.alertify.jpa.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import app.alertify.ai.AiInvocationContext;
import app.alertify.ai.AiInvocationContextHolder;

class AuditRevisionListenerTest {

    @AfterEach
    void clearSecurityContext() { SecurityContextHolder.clearContext(); }

    @Test
    void recordsSubjectAndUsernameFromAuthenticatedJwt() {
        Jwt jwt = Jwt.withTokenValue("token")
            .header("alg", "none")
            .subject("user-123")
            .claim("preferred_username", "administrator")
            .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
            jwt, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        ));

        AuditRevisionEntity revision = new AuditRevisionEntity();
        new AuditRevisionListener().newRevision(revision);

        assertThat(revision.getUserSubject()).isEqualTo("user-123");
        assertThat(revision.getUsername()).isEqualTo("administrator");
        assertThat(revision.isAiAssisted()).isFalse();
        assertThat(revision.getAiConversationId()).isNull();
    }

    @Test
    void recordsAiProvenanceAndActorWithoutASecurityContext() {
        AiInvocationContext context = new AiInvocationContext("user-123", "administrator", Set.of("ROLE_ADMIN"),
                71L, UUID.randomUUID());
        AuditRevisionEntity revision = new AuditRevisionEntity();

        AiInvocationContextHolder.runWith(context, () -> new AuditRevisionListener().newRevision(revision));

        assertThat(revision.getUserSubject()).isEqualTo("user-123");
        assertThat(revision.getUsername()).isEqualTo("administrator");
        assertThat(revision.isAiAssisted()).isTrue();
        assertThat(revision.getAiConversationId()).isEqualTo(71L);
    }
}
