package app.alertify.ai.api;

import app.alertify.procedures.api.ProcedureParameterValueRequest;
import org.springframework.ai.tool.annotation.ToolParam;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Strict-schema representation of a procedure parameter binding. */
public record AiProcedureParameterValue(
    @ToolParam(description = "Exact parameter key from the selected template; each key must appear only once in the parameters list. Replace its existing entry when changing source; do not append a duplicate.") @NotBlank @Size(max = 255) String parameterKey,
    @ToolParam(description = "Source requested for this parameter and allowed by the selected template. Supply its matching text value or positive resource ID; never substitute another source to complete the request.") @NotNull AiProcedureParameterSource source,
    @ToolParam(required = false, description = "Required when source is TEXT, including an empty string when that is the requested value. Otherwise use null; do not copy a reference's protected value.") @Size(max = 1048576) String textValue,
    @ToolParam(required = false, description = "Required positive ID of an existing compatible configuration when source is CONFIGURATION; 0 is invalid for that source. Otherwise use null; legacy null or 0 placeholders are ignored.") Long configurationId,
    @ToolParam(required = false, description = "Required positive ID of an existing compatible secret when source is SECRET; 0 is invalid for that source. Otherwise use null; legacy null or 0 placeholders are ignored. Reference metadata only, never a secret value.") Long secretId,
    @ToolParam(required = false, description = "Required positive ID of an existing procedure when source is PROCEDURE; 0 is invalid for that source. Otherwise use null; legacy null or 0 placeholders are ignored.") Long procedureId,
    @ToolParam(required = false, description = "Required positive ID of an existing pipe when source is PIPE; 0 is invalid for that source. Otherwise use null; legacy null or 0 placeholders are ignored.") Long pipeId
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
