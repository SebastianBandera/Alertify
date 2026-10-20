package app.alertify.ai.api;

import java.util.List;
import java.util.Set;

import app.alertify.pipes.api.PipeStepRequest;
import app.alertify.pipes.model.PipeOutcome;
import app.alertify.pipes.model.PipeStepPhase;
import app.alertify.pipes.model.PipeStepType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record AiPipeStep(
    @NotBlank @Size(max = 255) String key,
    @NotNull PipeStepType type,
    @Schema(description = "Optional execution phase; use null for MAIN") PipeStepPhase phase,
    @Positive long resourceId,
    @Schema(description = "Optional positive ISO-8601 duration, for example PT30S; use null or an empty string for the default") String timeout,
    @Schema(description = "Outcomes that permit the next step; use null or an empty set for SUCCESS") Set<@NotNull PipeOutcome> continueOn,
    @Schema(description = "Bindings from earlier procedure steps; use null or an empty list when there are none") List<@NotNull @Valid AiPipeBinding> bindings
) {

    public PipeStepRequest toRequest() {
        return new PipeStepRequest(key, type, phase, resourceId,
                AiRequestValues.optionalDuration(timeout, "timeout"), continueOn,
                bindings == null ? null : bindings.stream().map(AiPipeBinding::toRequest).toList());
    }
}
