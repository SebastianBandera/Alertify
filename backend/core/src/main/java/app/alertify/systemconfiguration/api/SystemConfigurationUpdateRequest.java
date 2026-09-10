package app.alertify.systemconfiguration.api;

import tools.jackson.databind.JsonNode;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record SystemConfigurationUpdateRequest(
    @NotNull @PositiveOrZero Long version,
    @NotNull JsonNode value
) {
}
