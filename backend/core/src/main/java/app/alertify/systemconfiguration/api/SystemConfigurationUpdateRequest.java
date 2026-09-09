package app.alertify.systemconfiguration.api;

import tools.jackson.databind.JsonNode;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record SystemConfigurationUpdateRequest(
    @NotNull @PositiveOrZero Long version,
    @Size(max = 2000) String description,
    @NotNull JsonNode value
) {
}
