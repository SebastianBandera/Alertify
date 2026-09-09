package app.alertify.configuration.api;

import java.time.Instant;
import java.util.Set;

import tools.jackson.databind.JsonNode;

import app.alertify.jpa.entity.ConfigurationValueType;

/**
 * Public configuration representation.
 */
public record ConfigurationResponse(
    Long id,
    long version,
    String name,
    String description,
    ConfigurationValueType valueType,
    JsonNode value,
    boolean writable,
    Set<TagResponse> tags,
    Instant createdAt,
    Instant updatedAt
) {
}
