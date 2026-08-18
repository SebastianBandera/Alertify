package app.alertify.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class CurrentLogActorTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void usesSystemOnlyWhenThereIsNoAuthenticatedJwt() {
        assertThat(CurrentLogActor.resolve())
            .isEqualTo(new LogActor("system", "system"));
    }

    @Test
    void marksMissingIdentityAsUnknownForAnAuthenticatedJwt() {
        Jwt token = mock(Jwt.class);
        JwtAuthenticationToken authentication = mock(JwtAuthenticationToken.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getToken()).thenReturn(token);
        when(authentication.getName()).thenReturn(null);
        when(token.getSubject()).thenReturn(null);
        when(token.getClaimAsString("preferred_username")).thenReturn(null);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        assertThat(CurrentLogActor.resolve()).isEqualTo(new LogActor(
            "unknown-authenticated-subject",
            "unknown-authenticated-user"
        ));
    }
}
