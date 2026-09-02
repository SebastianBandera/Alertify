package app.alertify.worker.contract;

import java.util.Locale;

/**
 * Identifies one independently composable operation family supported by a
 * worker. A worker may advertise any non-empty combination of these values.
 */
public enum WorkerCapability {

    STANDARD,
    PLAYWRIGHT;

    private static final String HEALTH_SERVICE_PREFIX = "app.alertify.worker.capability.";

    public String healthServiceName() {
        return HEALTH_SERVICE_PREFIX + name().toLowerCase(Locale.ROOT);
    }
}
