package app.alertify.ai.api;

import app.alertify.pipes.api.PipeBindingRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AiPipeBinding(
    @NotBlank @Size(max = 255) String targetParameterKey,
    @NotBlank @Size(max = 255) String sourceStepKey,
    @Size(max = 255) @Schema(description = "Procedure artifact output key; use this or sourceResultPointer, never both; otherwise use null or an empty string") String sourceOutputKey,
    @Size(max = 2000) @Schema(description = "JSON pointer into the source result; use this or sourceOutputKey, never both; otherwise use null or an empty string") String sourceResultPointer,
    @Size(max = 1048576) @Schema(description = "Optional value expression for a result-pointer binding; otherwise use null or an empty string") String valueExpression
) {

    public PipeBindingRequest toRequest() {
        return new PipeBindingRequest(targetParameterKey, sourceStepKey,
                AiRequestValues.optionalText(sourceOutputKey), AiRequestValues.optionalText(sourceResultPointer),
                AiRequestValues.optionalText(valueExpression));
    }
}
