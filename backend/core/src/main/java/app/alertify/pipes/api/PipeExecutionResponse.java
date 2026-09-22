package app.alertify.pipes.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import app.alertify.pipes.model.PipeExecutionStatus;
import app.alertify.pipes.model.PipeExecutionTrigger;
import app.alertify.pipes.model.PipeOutcome;

public record PipeExecutionResponse(
    Long id,
    UUID executionId,
    long pipeId,
    String pipeName,
    long pipeVersion,
    PipeExecutionStatus status,
    PipeOutcome outcome,
    PipeExecutionTrigger trigger,
    UUID rootExecutionId,
    UUID parentProcedureExecutionId,
    int depth,
    Instant startedAt,
    Instant finishedAt,
    Long durationMillis,
    String triggeredBy,
    String errorCode,
    List<PipeStepResultResponse> steps
) {
}
