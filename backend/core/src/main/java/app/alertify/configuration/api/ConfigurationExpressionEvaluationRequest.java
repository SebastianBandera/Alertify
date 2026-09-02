package app.alertify.configuration.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ConfigurationExpressionEvaluationRequest(
    Long configurationId,
    @Size(max = 200) String configurationName,
    @NotBlank @Size(max = 1_048_576) String expression
) {
}
