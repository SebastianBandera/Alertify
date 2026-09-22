package app.alertify.pipes.service;

import java.time.Duration;
import java.util.LinkedHashSet;

import app.alertify.pipes.api.PipeBindingResponse;
import app.alertify.pipes.api.PipeExecutionResponse;
import app.alertify.pipes.api.PipeResponse;
import app.alertify.pipes.api.PipeStepResponse;
import app.alertify.pipes.api.PipeStepResultResponse;
import app.alertify.pipes.model.Pipe;
import app.alertify.pipes.model.PipeExecution;
import app.alertify.pipes.model.PipeOutcome;
import app.alertify.pipes.model.PipeStep;
import app.alertify.pipes.model.PipeStepType;

final class PipeMapper {
    private PipeMapper() {
    }

    static PipeResponse response(Pipe pipe) {
        return new PipeResponse(pipe.getId(), pipe.getVersion(), pipe.getName(), pipe.getDescription(), pipe.isEnabled(),
                pipe.isConcurrentExecutionAllowed(), pipe.getSteps().stream().map(PipeMapper::step).toList(),
                pipe.getCreatedAt(), pipe.getUpdatedAt());
    }

    static PipeExecutionResponse execution(PipeExecution value) {
        Long duration = value.getFinishedAt() == null ? null
                : Duration.between(value.getStartedAt(), value.getFinishedAt()).toMillis();
        return new PipeExecutionResponse(value.getId(), value.getExecutionId(), value.getPipe().getId(), value.getPipeName(),
                value.getPipeVersion(), value.getStatus(), value.getOutcome(), value.getTrigger(), value.getRootExecutionId(),
                value.getParentProcedureExecutionId(), value.getDepth(), value.getStartedAt(), value.getFinishedAt(), duration,
                value.getTriggeredBy(), value.getErrorCode(), value.getSteps().stream().map(step -> new PipeStepResultResponse(
                        step.getStepKey(), step.getPosition(), step.getStepType(), step.getResourceId(), step.getResourceName(),
                        step.getStatus(), step.getOutcome(), step.getResourceExecutionId(), step.getStartedAt(),
                        step.getFinishedAt(), step.getErrorCode())).toList());
    }

    private static PipeStepResponse step(PipeStep value) {
        boolean alert = value.getStepType() == PipeStepType.ALERT;
        return new PipeStepResponse(value.getId(), value.getStepKey(), value.getPosition(), value.getStepType(),
                alert ? value.getAlert().getId() : value.getProcedure().getId(),
                alert ? value.getAlert().getName() : value.getProcedure().getName(),
                alert ? value.getAlert().isEnabled() : value.getProcedure().isEnabled(), Duration.ofMillis(value.getTimeoutMillis()),
                value.getContinueOn().stream().map(PipeOutcome::valueOf)
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)),
                value.getBindings().stream().map(binding -> new PipeBindingResponse(
                        binding.getTargetParameter().getParameterKey(), binding.getSourceStep().getStepKey(),
                        binding.getSourceOutput().getOutputKey())).toList());
    }
}
