package app.alertify.ai.api;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import app.alertify.systemconfiguration.api.SystemConfigurationUpdateRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record AiSystemConfigurationUpdateRequest(
    @NotNull @PositiveOrZero Long version,
    @NotBlank String valueJson
) {

    public SystemConfigurationUpdateRequest toRequest(JsonMapper json) {
        JsonNode value;
        try {
            value = json.readTree(valueJson);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("valueJson must contain valid JSON", exception);
        }
        return new SystemConfigurationUpdateRequest(version, value);
    }
}
