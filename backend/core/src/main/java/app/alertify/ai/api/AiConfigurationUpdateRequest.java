package app.alertify.ai.api;

import java.util.Set;

import tools.jackson.databind.json.JsonMapper;

import app.alertify.configuration.api.ConfigurationUpdateRequest;
import app.alertify.jpa.entity.ConfigurationValueType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record AiConfigurationUpdateRequest(
    @NotNull @PositiveOrZero Long version,
    @NotBlank @Size(max = 200) String name,
    @Size(max = 2000) String description,
    @NotNull ConfigurationValueType valueType,
    @NotNull @Valid AiConfigurationValue value,
    Set<@Positive Long> tagIds,
    boolean writable
) {

    public ConfigurationUpdateRequest toRequest(JsonMapper json) {
        return new ConfigurationUpdateRequest(version, name, description, valueType, value.toJsonNode(valueType, json), tagIds, writable);
    }
}
