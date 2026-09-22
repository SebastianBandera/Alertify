package app.alertify.pipes.api;

import java.time.Instant;
import java.util.UUID;

import app.alertify.pipes.model.PipeOutcome;
import app.alertify.pipes.model.PipeStepStatus;
import app.alertify.pipes.model.PipeStepType;

public record PipeStepResultResponse(
    String key,
    int position,
    PipeStepType type,
    long resourceId,
    String resourceName,
    PipeStepStatus status,
    PipeOutcome outcome,
    UUID resourceExecutionId,
    Instant startedAt,
    Instant finishedAt,
    String errorCode
) {
}
