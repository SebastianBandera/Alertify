package app.alertify.configuration.api;

import java.time.Instant;
import java.util.Set;

import tools.jackson.databind.JsonNode;

import app.alertify.jpa.entity.ConfigurationValueType;

public record ConfigurationResponse(
    Long id,
    long version,
    String name,
    String description,
    ConfigurationValueType valueType,
    JsonNode value,
    Set<TagResponse> tags,
    boolean systemManaged,
    boolean deletable,
    ConfigurationWarning changeWarning,
    Instant createdAt,
    Instant updatedAt
) {
}
