package app.alertify.ai.api;

import app.alertify.configuration.api.ConfigurationExpressionEvaluationRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AiConfigurationExpressionEvaluationRequest(
    @Schema(description = "Optional positive ID of the configuration being edited; use null or 0 for a new draft") Long configurationId,
    @Size(max = 200) @Schema(description = "Optional name of the configuration being edited, used to detect self-references") String configurationName,
    @NotBlank @Size(max = 1_048_576) String expression
) {

    public ConfigurationExpressionEvaluationRequest toRequest() {
        return new ConfigurationExpressionEvaluationRequest(AiRequestValues.optionalPositive(configurationId),
                AiRequestValues.optionalText(configurationName), expression);
    }
}
