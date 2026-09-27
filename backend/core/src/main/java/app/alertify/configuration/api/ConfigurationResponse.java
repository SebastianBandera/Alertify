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
    String binaryFileName,
    String binaryContentType,
    Long binarySize,
    Long binaryZipSize,
    boolean writable,
    Set<TagResponse> tags,
    Instant createdAt,
    Instant updatedAt,
    int usageCount
) {
    public ConfigurationResponse(Long id, long version, String name, String description, ConfigurationValueType valueType,
            JsonNode value, String binaryFileName, String binaryContentType, Long binarySize, Long binaryZipSize,
            boolean writable, Set<TagResponse> tags, Instant createdAt, Instant updatedAt) {
        this(id, version, name, description, valueType, value, binaryFileName, binaryContentType, binarySize,
                binaryZipSize, writable, tags, createdAt, updatedAt, 0);
    }

    public ConfigurationResponse(Long id, long version, String name, String description, ConfigurationValueType valueType,
            JsonNode value, boolean writable, Set<TagResponse> tags, Instant createdAt, Instant updatedAt) {
        this(id, version, name, description, valueType, value, null, null, null, null, writable, tags, createdAt, updatedAt);
    }

    public ConfigurationResponse withUsageCount(int count) {
        return new ConfigurationResponse(id, version, name, description, valueType, value, binaryFileName,
                binaryContentType, binarySize, binaryZipSize, writable, tags, createdAt, updatedAt, count);
    }
}
