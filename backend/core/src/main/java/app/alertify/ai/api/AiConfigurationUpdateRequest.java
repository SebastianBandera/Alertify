package app.alertify.ai.api;

import java.util.Set;

import org.springframework.ai.tool.annotation.ToolParam;

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
    @ToolParam(description = "Complete replacement value for the selected valueType, not a patch. If editing only description, name, tags or writable, copy the active value from the fresh configuration_get/search result. For STRING, a read value QA-INITIAL must remain stringValue=QA-INITIAL; an empty string would overwrite it. Only members for other value types use neutral placeholders.") @NotNull @Valid AiConfigurationValue value,
    Set<@Positive Long> tagIds,
    @ToolParam(description = "Allows resource executions to persist changed values into this configuration. This flag does not control administrative editing; writable=false still allows authorized edits to value, description, name and tags. Preserve the current flag unless the user asks to change it; do not enable it to perform an administrative edit.") boolean writable
) {

    public ConfigurationUpdateRequest toRequest(JsonMapper json) {
        return new ConfigurationUpdateRequest(version, name, description, valueType, value.toJsonNode(valueType, json), tagIds, writable);
    }
}
