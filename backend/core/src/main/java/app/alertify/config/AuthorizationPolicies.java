package app.alertify.config;

/** Shared method-security expressions used by HTTP controllers and AI tools. */
public final class AuthorizationPolicies {

    public static final String ADMIN = "hasRole('ADMIN')";
    public static final String ADMIN_OR_DASHBOARD = "hasRole('ADMIN') or hasRole('DASHBOARD')";
    public static final String DASHBOARD_RUN = "hasRole('DASHBOARD') and hasRole('DASHBOARD_RUN')";
    public static final String AUTHENTICATED = "isAuthenticated()";

    private AuthorizationPolicies() {
    }
}
