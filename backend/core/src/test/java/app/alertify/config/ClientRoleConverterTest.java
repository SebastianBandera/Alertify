package app.alertify.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class ClientRoleConverterTest {

    private final ClientRoleConverter converter = new ClientRoleConverter("monitoring-api");

    @Test
    void convertsOnlyRolesFromConfiguredClient() {
        Jwt jwt = jwtWithResourceAccess(Map.of(
            "monitoring-api", Map.of("roles", List.of("DASHBOARD", "ADMIN")),
            "other-api", Map.of("roles", List.of("OTHER_ADMIN"))
        ));

        assertThat(converter.convert(jwt))
            .extracting(GrantedAuthority::getAuthority)
            .containsExactly("ROLE_DASHBOARD", "ROLE_ADMIN");
    }

    @Test
    void returnsNoAuthoritiesWhenConfiguredClientIsAbsent() {
        Jwt jwt = jwtWithResourceAccess(Map.of(
            "other-api", Map.of("roles", List.of("ADMIN"))
        ));

        assertThat(converter.convert(jwt)).isEmpty();
    }

    @Test
    void returnsNoAuthoritiesWhenResourceAccessIsAbsent() {
        Jwt jwt = Jwt.withTokenValue("token")
            .header("alg", "none")
            .claim("sub", "user")
            .build();

        assertThat(converter.convert(jwt)).isEmpty();
    }

    private static Jwt jwtWithResourceAccess(Map<String, Object> resourceAccess) {
        return Jwt.withTokenValue("token")
            .header("alg", "none")
            .claim("resource_access", resourceAccess)
            .build();
    }
}
