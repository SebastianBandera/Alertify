package app.alertify.pipes.api;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import app.alertify.pipes.model.PipeOutcome;
import app.alertify.pipes.model.PipeStepType;
import app.alertify.pipes.model.PipeStepPhase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record PipeStepRequest(
    @NotBlank @Size(max = 255) String key,
    @NotNull PipeStepType type,
    PipeStepPhase phase,
    @Positive long resourceId,
    Duration timeout,
    Set<@NotNull PipeOutcome> continueOn,
    List<@Valid PipeBindingRequest> bindings
) {
    public PipeStepRequest {
        phase = phase == null ? PipeStepPhase.MAIN : phase;
        bindings = bindings == null ? List.of() : List.copyOf(bindings);
        continueOn = continueOn == null || continueOn.isEmpty() ? Set.of(PipeOutcome.SUCCESS) : Set.copyOf(continueOn);
    }
}
