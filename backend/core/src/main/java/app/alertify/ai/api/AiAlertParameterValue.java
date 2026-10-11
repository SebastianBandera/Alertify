package app.alertify.ai.api;

import app.alertify.alerts.api.AlertParameterValueRequest;
import app.alertify.alerts.template.annotation.AlertParameterSource;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Strict-schema representation of an alert parameter binding. */
public record AiAlertParameterValue(
    @NotBlank @Size(max = 255) String parameterKey,
    @NotNull AiAlertParameterSource source,
    @Size(max = 1048576) @Schema(description = "Value used only when source is TEXT; otherwise use null or an empty string") String textValue,
    @Schema(description = "Positive configuration ID used only when source is CONFIGURATION; otherwise use null or 0") Long configurationId,
    @Schema(description = "Positive secret ID used only when source is SECRET; otherwise use null or 0") Long secretId,
    @Schema(description = "Positive procedure ID used only when source is PROCEDURE; otherwise use null or 0") Long procedureId
) {

    public AlertParameterValueRequest toRequest() {
        if (source == null)
            throw new IllegalArgumentException("Alert parameter source is required");

        return switch (source) {
            case TEXT -> new AlertParameterValueRequest(parameterKey, AlertParameterSource.TEXT,
                    requiredText(textValue), null, null, null);
            case CONFIGURATION -> new AlertParameterValueRequest(parameterKey, AlertParameterSource.CONFIGURATION,
                    null, positive(configurationId, "configurationId"), null, null);
            case SECRET -> new AlertParameterValueRequest(parameterKey, AlertParameterSource.SECRET,
                    null, null, positive(secretId, "secretId"), null);
            case PROCEDURE -> new AlertParameterValueRequest(parameterKey, AlertParameterSource.PROCEDURE,
                    null, null, null, positive(procedureId, "procedureId"));
        };
    }

    private static String requiredText(String value) {
        if (value == null)
            throw new IllegalArgumentException("textValue must be supplied when source is TEXT");

        return value;
    }

    private static Long positive(Long value, String field) {
        if (value == null || value <= 0)
            throw new IllegalArgumentException(field + " must be a positive ID for the selected source");

        return value;
    }
}
