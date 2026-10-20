package app.alertify.ai.api;

import java.time.Duration;
import java.time.format.DateTimeParseException;

final class AiRequestValues {

    private AiRequestValues() { }

    static Long optionalPositive(Long value) {
        return value == null || value <= 0 ? null : value;
    }

    static Integer optionalPositive(Integer value) {
        return value == null || value <= 0 ? null : value;
    }

    static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    static Duration optionalDuration(String value, String field) {
        if (value == null || value.isBlank())
            return null;

        try {
            Duration duration = Duration.parse(value);
            if (duration.isZero() || duration.isNegative() || duration.toMillis() == 0)
                throw new IllegalArgumentException(field + " must be a positive ISO-8601 duration");

            return duration;
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(field + " must be a valid ISO-8601 duration", exception);
        }
    }
}
