package app.alertify.alerts.execution;

import java.util.Optional;

import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;

import app.alertify.api.error.MaintenanceModeActiveException;
import app.alertify.jpa.entity.SystemConfiguration;
import app.alertify.jpa.repository.SystemConfigurationRepository;

/**
 * Reads the {@code MAINTENANCE_MODE} system configuration to decide whether
 * new alert or procedure executions should currently be blocked, regardless
 * of trigger (cron, manual, hook, or a nested invocation). Already-running
 * executions are never affected.
 *
 * <p>{@link #isActive()} always reads the configuration fresh (no caching),
 * so a toggle takes effect on the very next trigger. A missing or malformed
 * configuration fails open (returns {@code false}): the same criterion
 * {@link CronQuietHoursService} already uses for the cron quiet-hours window,
 * for consistency - blocking the whole system because of a corrupt value
 * would be worse than occasionally letting an execution through.
 */
@Service
public class MaintenanceModeService {

    private static final String CONFIGURATION_NAME = "MAINTENANCE_MODE";

    private final SystemConfigurationRepository repository;

    public MaintenanceModeService(SystemConfigurationRepository repository) {
        this.repository = repository;
    }

    public boolean isActive() {
        Optional<SystemConfiguration> configuration = repository.findByNameIgnoreCase(CONFIGURATION_NAME);
        if (configuration.isEmpty())
            return false;

        JsonNode enabled = configuration.get().getValue().get("enabled");
        return enabled != null && enabled.isBoolean() && enabled.booleanValue();
    }

    public void assertNotActive() {
        if (isActive())
            throw new MaintenanceModeActiveException("The system is in maintenance mode and is not accepting new executions");
    }
}
