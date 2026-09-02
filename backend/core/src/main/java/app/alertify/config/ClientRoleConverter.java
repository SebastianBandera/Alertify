package app.alertify.config;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Converts Keycloak client roles from the JWT {@code resource_access} claim
 * into Spring Security {@code ROLE_} authorities for the configured client.
 */
final class ClientRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final String RESOURCE_ACCESS_CLAIM = "resource_access";
    private static final String ROLES_CLAIM = "roles";
    private static final String AUTHORITY_PREFIX = "ROLE_";

    private final String clientId;

    ClientRoleConverter(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("The roles client ID must not be blank");
        }
        this.clientId = clientId;
    }

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Map<String, Object> resourceAccess = jwt.getClaimAsMap(RESOURCE_ACCESS_CLAIM);
        if (resourceAccess == null) {
            return List.of();
        }

        Object clientAccessValue = resourceAccess.get(clientId);
        if (!(clientAccessValue instanceof Map<?, ?> clientAccess)) {
            return List.of();
        }

        Object rolesValue = clientAccess.get(ROLES_CLAIM);
        if (!(rolesValue instanceof Collection<?> roles)) {
            return List.of();
        }

        return roles.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(role -> !role.isBlank())
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(AUTHORITY_PREFIX + role))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }
}
