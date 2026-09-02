package app.alertify.configuration.service;

/**
 * Shared Redis cache names used by lookup and invalidation code to avoid
 * configuration drift between annotations and cache management.
 */
public final class ConfigurationCacheNames {

    public static final String BY_ID = "application-configurations-by-id";
    public static final String BY_NAME = "application-configurations-by-name";

    private ConfigurationCacheNames() {
    }
}
