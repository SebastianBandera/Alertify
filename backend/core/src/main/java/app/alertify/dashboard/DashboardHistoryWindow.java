package app.alertify.dashboard;

import org.springframework.stereotype.Component;

import app.alertify.jpa.repository.SystemConfigurationRepository;
import tools.jackson.databind.JsonNode;

/**
 * Length of the look-back window summarized on every dashboard tile, read from
 * the {@code DASHBOARD_HISTORY_WINDOW} system configuration on each use so a
 * change applies to the next tile assembled. A missing or malformed value falls
 * back to the default: the board should keep working rather than fail over a
 * presentation setting.
 */
@Component
public class DashboardHistoryWindow {

    public static final String CONFIGURATION_NAME = "DASHBOARD_HISTORY_WINDOW";
    public static final int DEFAULT_DAYS = 10;
    public static final int MIN_DAYS = 1;
    /* Keeps the window start well inside the timestamp range the queries can bind. */
    public static final int MAX_DAYS = 365;

    private final SystemConfigurationRepository repository;

    public DashboardHistoryWindow(SystemConfigurationRepository repository) {
        this.repository = repository;
    }

    public int days() {
        return repository.findByNameIgnoreCase(CONFIGURATION_NAME)
                .map(configuration -> validDays(configuration.getValue()))
                .orElse(DEFAULT_DAYS);
    }

    /** Whether {@code value} is a well-formed {@code {"days": N}} with a whole number of days in range. */
    public static boolean isValid(JsonNode value) {
        JsonNode days = value == null || !value.isObject() ? null : value.get("days");
        return days != null && days.isIntegralNumber() && days.canConvertToInt()
                && days.intValue() >= MIN_DAYS && days.intValue() <= MAX_DAYS;
    }

    private static int validDays(JsonNode value) {
        return isValid(value) ? value.get("days").intValue() : DEFAULT_DAYS;
    }
}
