package app.alertify.alerts.execution;

import java.time.Clock;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;

import tools.jackson.databind.JsonNode;

import app.alertify.jpa.entity.SystemConfiguration;
import app.alertify.jpa.repository.SystemConfigurationRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.system.CronQuietHoursTransitionEvent;

/**
 * Reads the {@code CRON_QUIET_HOURS} system configuration to decide whether
 * cron-triggered alerts should currently be skipped. Manual and hook-triggered
 * executions never consult this service.
 *
 * <p>{@link #isQuietNow()} always reads the configuration fresh (no caching),
 * so a range change takes effect on the very next cron firing decision. A
 * missing or malformed configuration fails open (returns {@code false}):
 * silencing every cron alert because of a corrupt value would be worse than
 * occasionally firing during an intended quiet window.
 *
 * <p>The periodic {@link #checkForTransition()} check exists only to log the
 * {@code CRON_QUIET_PERIOD_STARTED}/{@code CRON_QUIET_PERIOD_ENDED} events
 * once per transition (including transitions caused by an admin editing the
 * range, not just the clock crossing start/end) - it does not gate anything
 * itself.
 */
@Service
public class CronQuietHoursService {

    private static final String CONFIGURATION_NAME = "CRON_QUIET_HOURS";

    private final SystemConfigurationRepository repository;
    private final ApplicationEventLogger eventLogger;
    private final ApplicationEventPublisher applicationEventPublisher;
    private volatile Clock clock = Clock.systemDefaultZone();
    private volatile boolean quiet;
    private volatile boolean initialized;

    public CronQuietHoursService(SystemConfigurationRepository repository, ApplicationEventLogger eventLogger, ApplicationEventPublisher applicationEventPublisher) {
        this.repository = repository;
        this.eventLogger = eventLogger;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    void setClockForTesting(Clock clock) {
        this.clock = clock;
    }

    public boolean isQuietNow() {
        return computeQuiet();
    }

    @Scheduled(fixedDelay = 60_000)
    void checkForTransition() {
        boolean currentlyQuiet = computeQuiet();
        if (!initialized) {
            quiet = currentlyQuiet;
            initialized = true;
            return;
        }
        if (currentlyQuiet == quiet)
            return;

        quiet = currentlyQuiet;
        eventLogger.success(currentlyQuiet ? "CRON_QUIET_PERIOD_STARTED" : "CRON_QUIET_PERIOD_ENDED", Map.of());
        applicationEventPublisher.publishEvent(new CronQuietHoursTransitionEvent(currentlyQuiet));
    }

    private boolean computeQuiet() {
        Optional<SystemConfiguration> configuration = repository.findByNameIgnoreCase(CONFIGURATION_NAME);
        if (configuration.isEmpty())
            return false;

        JsonNode value = configuration.get().getValue();
        JsonNode enabled = value.get("enabled");
        if (enabled == null || !enabled.isBoolean() || !enabled.booleanValue())
            return false;

        try {
            LocalTime start = parseTime(value.get("start"));
            LocalTime end = parseTime(value.get("end"));
            return isWithin(LocalTime.now(clock), start, end);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static LocalTime parseTime(JsonNode node) {
        if (node == null || !node.isString())
            throw new IllegalArgumentException("Expected a string time value");

        try {
            return LocalTime.parse(node.stringValue());
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(exception);
        }
    }

    static boolean isWithin(LocalTime now, LocalTime start, LocalTime end) {
        if (start.equals(end))
            return false;
        if (start.isBefore(end))
            return !now.isBefore(start) && now.isBefore(end);

        return !now.isBefore(start) || now.isBefore(end);
    }
}
