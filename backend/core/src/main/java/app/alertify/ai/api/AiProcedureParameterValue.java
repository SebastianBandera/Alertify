package app.alertify.ai.api;

import app.alertify.procedures.api.ProcedureParameterValueRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Strict-schema representation of a procedure parameter binding. */
public record AiProcedureParameterValue(
    @NotBlank @Size(max = 255) String parameterKey,
    @NotNull AiProcedureParameterSource source,
    @Size(max = 1048576) @Schema(description = "Value used only when source is TEXT; otherwise use null or an empty string") String textValue,
    @Schema(description = "Positive configuration ID used only when source is CONFIGURATION; otherwise use null or 0") Long configurationId,
    @Schema(description = "Positive secret ID used only when source is SECRET; otherwise use null or 0") Long secretId,
    @Schema(description = "Positive procedure ID used only when source is PROCEDURE; otherwise use null or 0") Long procedureId,
    @Schema(description = "Positive pipe ID used only when source is PIPE; otherwise use null or 0") Long pipeId
) {

    public ProcedureParameterValueRequest toRequest() {
        if (source == null)
            throw new IllegalArgumentException("Procedure parameter source is required");

        return switch (source) {
            case TEXT -> new ProcedureParameterValueRequest(parameterKey, source.source(), requiredText(textValue), null, null, null, null);
            case CONFIGURATION -> new ProcedureParameterValueRequest(parameterKey, source.source(), null,
                    positive(configurationId, "configurationId"), null, null, null);
            case SECRET -> new ProcedureParameterValueRequest(parameterKey, source.source(), null, null,
                    positive(secretId, "secretId"), null, null);
            case PROCEDURE -> new ProcedureParameterValueRequest(parameterKey, source.source(), null, null, null,
                    positive(procedureId, "procedureId"), null);
            case PIPE -> new ProcedureParameterValueRequest(parameterKey, source.source(), null, null, null, null,
                    positive(pipeId, "pipeId"));
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
