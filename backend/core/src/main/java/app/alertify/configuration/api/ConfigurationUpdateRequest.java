package app.alertify.configuration.api;

import java.util.Set;

import tools.jackson.databind.JsonNode;

import app.alertify.jpa.entity.ConfigurationValueType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record ConfigurationUpdateRequest(
    @NotNull @PositiveOrZero Long version,
    @NotBlank @Size(max = 200) String name,
    @Size(max = 2000) String description,
    @NotNull ConfigurationValueType valueType,
    @NotNull JsonNode value,
    Set<@Positive Long> tagIds,
    boolean writable
) {

    public ConfigurationUpdateRequest(Long version, String name, String description, ConfigurationValueType valueType, JsonNode value, Set<Long> tagIds) {
        this(version, name, description, valueType, value, tagIds, false);
    }
}
