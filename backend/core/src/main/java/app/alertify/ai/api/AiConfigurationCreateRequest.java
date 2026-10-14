package app.alertify.ai.api;

import java.util.Set;

import org.springframework.ai.tool.annotation.ToolParam;

import tools.jackson.databind.json.JsonMapper;

import app.alertify.configuration.api.ConfigurationCreateRequest;
import app.alertify.jpa.entity.ConfigurationValueType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record AiConfigurationCreateRequest(
    @NotBlank @Size(max = 200) String name,
    @Size(max = 2000) String description,
    @NotNull ConfigurationValueType valueType,
    @NotNull @Valid AiConfigurationValue value,
    Set<@Positive Long> tagIds,
    @ToolParam(description = "Allows resource executions to persist changed values into this configuration. This flag does not control administrative creation or editing; writable=false still allows authorized administrative edits. Set it according to the user's request.") boolean writable
) {

    public ConfigurationCreateRequest toRequest(JsonMapper json) {
        return new ConfigurationCreateRequest(name, description, valueType, value.toJsonNode(valueType, json), tagIds, writable);
    }
}
