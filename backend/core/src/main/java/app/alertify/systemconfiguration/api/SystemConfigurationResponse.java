package app.alertify.systemconfiguration.api;

import java.time.Instant;

import tools.jackson.databind.JsonNode;

/**
 * Public representation of a system configuration. {@code value} is
 * {@code null} whenever {@code valueHidden} is {@code true} (e.g. KEY_PART):
 * the actual value never enters the API response or Redis cache for those
 * entries. Non-hidden entries (e.g. CRON_QUIET_HOURS) return their value
 * plainly so the frontend can render a proper form for them.
 */
public record SystemConfigurationResponse(
    Long id,
    long version,
    String name,
    String description,
    JsonNode value,
    boolean valueHidden,
    Instant createdAt,
    Instant updatedAt
) {
}
