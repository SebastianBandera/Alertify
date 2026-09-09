package app.alertify.systemconfiguration.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Regenerates a system configuration's value entirely server-side (32 random
 * bytes, hex-encoded) - no value ever needs to travel from the browser for
 * this case, only when an admin enters a specific value manually.
 */
public record SystemConfigurationRegenerateRequest(
    @NotNull @PositiveOrZero Long version
) {
}
