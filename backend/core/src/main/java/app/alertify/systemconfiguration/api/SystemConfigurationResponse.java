package app.alertify.systemconfiguration.api;

import java.time.Instant;

/**
 * Public representation of a system configuration. The value is never
 * included: every entry in this table is sensitive by convention, so only
 * its metadata is exposed.
 */
public record SystemConfigurationResponse(
    Long id,
    long version,
    String name,
    String description,
    Instant createdAt,
    Instant updatedAt
) {
}
