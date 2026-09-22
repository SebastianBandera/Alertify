package app.alertify.pipes.api;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import app.alertify.pipes.model.PipeOutcome;
import app.alertify.pipes.model.PipeStepType;

public record PipeStepResponse(
    Long id,
    String key,
    int position,
    PipeStepType type,
    long resourceId,
    String resourceName,
    boolean resourceEnabled,
    Duration timeout,
    Set<PipeOutcome> continueOn,
    List<PipeBindingResponse> bindings
) {
}
