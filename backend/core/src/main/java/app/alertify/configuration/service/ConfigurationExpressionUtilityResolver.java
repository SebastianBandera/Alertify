package app.alertify.configuration.service;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

import app.alertify.api.error.InvalidConfigurationExpressionException;

/**
 * Resolves built-in {@code utils.NAME} expression values from one timestamp
 * snapshot so every utility referenced by the same evaluation is consistent.
 */
@Component
class ConfigurationExpressionUtilityResolver {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static final List<String> NAMES = List.of(
            "YEAR",
            "MONTH",
            "MONTH_PADDED",
            "DAY",
            "DAY_PADDED",
            "HOUR",
            "HOUR_PADDED",
            "MINUTE",
            "MINUTE_PADDED",
            "SECOND",
            "SECOND_PADDED",
            "DATE",
            "TIME",
            "DATE_TIME",
            "OFFSET_DATE_TIME",
            "DAY_OF_WEEK",
            "DAY_OF_YEAR",
            "WEEK_OF_YEAR",
            "WEEK_YEAR",
            "EPOCH_SECONDS",
            "EPOCH_MILLIS",
            "TIME_ZONE",
            "UTC_OFFSET"
    );

    List<String> names() {
        return NAMES;
    }

    ZonedDateTime snapshot() {
        return ZonedDateTime.now();
    }

    void ensureSupported(String name) {
        if (!NAMES.contains(name)) {
            throw new InvalidConfigurationExpressionException(
                    "Unsupported configuration expression utility '" + name + "'"
            );
        }
    }

    String resolve(String name, ZonedDateTime now) {
        ensureSupported(name);
        return switch (name) {
            case "YEAR" -> Integer.toString(now.getYear());
            case "MONTH" -> Integer.toString(now.getMonthValue());
            case "MONTH_PADDED" -> twoDigits(now.getMonthValue());
            case "DAY" -> Integer.toString(now.getDayOfMonth());
            case "DAY_PADDED" -> twoDigits(now.getDayOfMonth());
            case "HOUR" -> Integer.toString(now.getHour());
            case "HOUR_PADDED" -> twoDigits(now.getHour());
            case "MINUTE" -> Integer.toString(now.getMinute());
            case "MINUTE_PADDED" -> twoDigits(now.getMinute());
            case "SECOND" -> Integer.toString(now.getSecond());
            case "SECOND_PADDED" -> twoDigits(now.getSecond());
            case "DATE" -> now.toLocalDate().toString();
            case "TIME" -> TIME_FORMATTER.format(now);
            case "DATE_TIME" -> DATE_TIME_FORMATTER.format(now);
            case "OFFSET_DATE_TIME" -> DATE_TIME_FORMATTER.format(now) + now.getOffset().getId();
            case "DAY_OF_WEEK" -> Integer.toString(now.getDayOfWeek().getValue());
            case "DAY_OF_YEAR" -> Integer.toString(now.getDayOfYear());
            case "WEEK_OF_YEAR" -> Integer.toString(now.get(WeekFields.ISO.weekOfWeekBasedYear()));
            case "WEEK_YEAR" -> Integer.toString(now.get(WeekFields.ISO.weekBasedYear()));
            case "EPOCH_SECONDS" -> Long.toString(now.toEpochSecond());
            case "EPOCH_MILLIS" -> Long.toString(now.toInstant().toEpochMilli());
            case "TIME_ZONE" -> now.getZone().getId();
            case "UTC_OFFSET" -> now.getOffset().getId();
            default -> throw new IllegalStateException("Unexpected utility: " + name.toUpperCase(Locale.ROOT));
        };
    }

    private static String twoDigits(int value) {
        return String.format(Locale.ROOT, "%02d", value);
    }
}
