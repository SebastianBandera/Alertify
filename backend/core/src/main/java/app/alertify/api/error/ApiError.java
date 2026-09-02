package app.alertify.api.error;

import java.time.Instant;
import java.util.Map;

/**
 * Stable error body returned by every handled API failure. The code drives
 * frontend localization while parameters provide safe interpolation values.
 */
public record ApiError(
    Instant timestamp,
    int status,
    String code,
    String message,
    Map<String, String> fieldErrors,
    Map<String, String> parameters
) {
}
