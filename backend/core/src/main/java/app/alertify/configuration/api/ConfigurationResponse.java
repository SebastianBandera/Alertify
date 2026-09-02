package app.alertify.configuration.api;

import java.time.Instant;
import java.util.Set;

import tools.jackson.databind.JsonNode;

import app.alertify.jpa.entity.ConfigurationValueType;

/**
 * Public configuration representation. Sensitive system values are returned
 * as {@code null} with {@code valueHidden=true}, so they cannot enter the API
 * response or Redis cache.
 */
public record ConfigurationResponse(
    Long id,
    long version,
    String name,
    String description,
    ConfigurationValueType valueType,
    JsonNode value,
    boolean valueHidden,
    boolean writable,
    Set<TagResponse> tags,
    boolean systemManaged,
    boolean deletable,
    ConfigurationWarning changeWarning,
    Instant createdAt,
    Instant updatedAt
) {
    public ConfigurationResponse(
        Long id,
        long version,
        String name,
        String description,
        ConfigurationValueType valueType,
        JsonNode value,
        boolean valueHidden,
        Set<TagResponse> tags,
        boolean systemManaged,
        boolean deletable,
        ConfigurationWarning changeWarning,
        Instant createdAt,
        Instant updatedAt
    ) {
        this(
            id, version, name, description, valueType, value, valueHidden, false,
            tags, systemManaged, deletable, changeWarning, createdAt, updatedAt
        );
    }
}
