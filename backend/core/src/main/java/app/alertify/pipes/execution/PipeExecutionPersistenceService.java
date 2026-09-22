package app.alertify.pipes.execution;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.jpa.repository.PipeExecutionRepository;
import app.alertify.jpa.repository.PipeRepository;
import app.alertify.jpa.repository.PipeStepResultRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.pipes.model.Pipe;
import app.alertify.pipes.model.PipeExecution;
import app.alertify.pipes.model.PipeExecutionStatus;
import app.alertify.pipes.model.PipeExecutionTrigger;
import app.alertify.pipes.model.PipeOutcome;
import app.alertify.pipes.model.PipeStepResult;
import app.alertify.pipes.model.PipeStepStatus;

@Service
public class PipeExecutionPersistenceService {
    private final PipeRepository pipeRepository;
    private final PipeExecutionRepository executionRepository;
    private final PipeStepResultRepository stepRepository;
    private final ApplicationEventLogger eventLogger;

    public PipeExecutionPersistenceService(PipeRepository pipeRepository, PipeExecutionRepository executionRepository, PipeStepResultRepository stepRepository, ApplicationEventLogger eventLogger) {
        this.pipeRepository = pipeRepository;
        this.executionRepository = executionRepository;
        this.stepRepository = stepRepository;
        this.eventLogger = eventLogger;
    }

    @Transactional(readOnly = true)
    public Pipe definition(long pipeId) {
        return pipeRepository.findDetailedById(pipeId)
                .orElseThrow(() -> new ResourceNotFoundException("Pipe " + pipeId + " was not found"));
    }

    @Transactional
    public void start(UUID executionId, Pipe pipe, PipeExecutionTrigger trigger, UUID rootExecutionId, UUID parentProcedureExecutionId, int depth, String triggeredBy) {
        Pipe managed = pipeRepository.findDetailedById(pipe.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Pipe " + pipe.getId() + " was not found"));
        PipeExecution execution = new PipeExecution(executionId, managed, trigger, rootExecutionId, parentProcedureExecutionId, depth, triggeredBy);
        managed.getSteps().forEach(step -> execution.addStep(new PipeStepResult(execution, step)));
        executionRepository.saveAndFlush(execution);
        eventLogger.successAfterCommit("PIPE_EXECUTION_STARTED", Map.of("executionId", executionId, "pipeId", pipe.getId(), "pipeName", pipe.getName()));
    }

    @Transactional
    public void startStep(UUID executionId, String stepKey) {
        PipeStepResult step = step(executionId, stepKey);
        step.start();
        stepRepository.flush();
    }

    @Transactional
    public void completeStep(UUID executionId, String stepKey, PipeStepStatus status, PipeOutcome outcome, UUID resourceExecutionId, String errorCode) {
        PipeStepResult step = step(executionId, stepKey);
        step.complete(status, outcome, resourceExecutionId, errorCode);
        stepRepository.flush();
    }

    @Transactional
    public void finish(UUID executionId, PipeExecutionStatus status, PipeOutcome outcome, String errorCode) {
        PipeExecution execution = execution(executionId);
        if (execution.getStatus() == PipeExecutionStatus.RUNNING)
            execution.finish(status, outcome, errorCode);

        executionRepository.flush();
        eventLogger.successAfterCommit("PIPE_EXECUTION_COMPLETED", Map.of("executionId", executionId,
                "pipeId", execution.getPipe().getId(), "status", execution.getStatus().name(), "outcome", execution.getOutcome().name()));
    }

    @Transactional
    public void failRunning(UUID executionId, String errorCode) {
        PipeExecution execution = executionRepository.findByExecutionId(executionId).orElse(null);
        if (execution != null && execution.getStatus() == PipeExecutionStatus.RUNNING) {
            execution.finish(PipeExecutionStatus.FAILED, PipeOutcome.ERROR, errorCode);
            executionRepository.flush();
        }
    }

    private PipeExecution execution(UUID executionId) {
        return executionRepository.findByExecutionId(executionId)
                .orElseThrow(() -> new IllegalStateException("Pipe execution " + executionId + " was not found"));
    }

    private PipeStepResult step(UUID executionId, String stepKey) {
        return execution(executionId).getSteps().stream().filter(value -> value.getStepKey().equals(stepKey)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Pipe step " + stepKey + " was not found"));
    }
}
