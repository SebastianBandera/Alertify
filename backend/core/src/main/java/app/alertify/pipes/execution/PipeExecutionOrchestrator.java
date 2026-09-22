package app.alertify.pipes.execution;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.stereotype.Service;

import app.alertify.alerts.execution.AlertExecutionOrchestrator;
import app.alertify.alerts.execution.AlertExecutionStatus;
import app.alertify.alerts.execution.MaintenanceModeService;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.pipes.PipeExecutionException;
import app.alertify.pipes.model.Pipe;
import app.alertify.pipes.model.PipeExecutionStatus;
import app.alertify.pipes.model.PipeExecutionTrigger;
import app.alertify.pipes.model.PipeOutcome;
import app.alertify.pipes.model.PipeStep;
import app.alertify.pipes.model.PipeStepStatus;
import app.alertify.pipes.model.PipeStepType;
import app.alertify.procedures.ProcedureDepthExceededException;
import app.alertify.procedures.ProcedureDisabledException;
import app.alertify.procedures.ProcedureExecutionException;
import app.alertify.procedures.execution.ProcedureExecutionOrchestrator;
import app.alertify.procedures.execution.ProcedureExecutionOrchestrator.ArtifactLocation;
import app.alertify.procedures.execution.ProcedureExecutionProperties;
import app.alertify.worker.grpc.InvokePipeResponse;
import app.alertify.worker.grpc.PipeInvocationFailure;
import app.alertify.worker.grpc.PipeInvocationFailureKind;
import app.alertify.worker.grpc.PipeInvocationResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class PipeExecutionOrchestrator implements AutoCloseable {
    private final PipeExecutionPersistenceService persistence;
    private final AlertExecutionOrchestrator alertOrchestrator;
    private final ProcedureExecutionOrchestrator procedureOrchestrator;
    private final MaintenanceModeService maintenanceModeService;
    private final ProcedureExecutionProperties properties;
    private final ApplicationEventLogger eventLogger;
    private final JsonMapper jsonMapper;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentMap<Long, PipeGate> gates = new ConcurrentHashMap<>();

    public PipeExecutionOrchestrator(PipeExecutionPersistenceService persistence, AlertExecutionOrchestrator alertOrchestrator, ProcedureExecutionOrchestrator procedureOrchestrator, MaintenanceModeService maintenanceModeService, ProcedureExecutionProperties properties, ApplicationEventLogger eventLogger, JsonMapper jsonMapper) {
        this.persistence = persistence;
        this.alertOrchestrator = alertOrchestrator;
        this.procedureOrchestrator = procedureOrchestrator;
        this.maintenanceModeService = maintenanceModeService;
        this.properties = properties;
        this.eventLogger = eventLogger;
        this.jsonMapper = jsonMapper;
    }

    public UUID triggerManual(long pipeId, String triggeredBy) {
        maintenanceModeService.assertNotActive();
        Pipe pipe = persistence.definition(pipeId);
        if (!enter(pipeId, pipe.isConcurrentExecutionAllowed()))
            throw new PipeExecutionException("Pipe '" + pipe.getName() + "' is already running and does not allow concurrent executions");

        UUID executionId = UUID.randomUUID();
        Instant deadline = Instant.now().plus(Duration.ofMinutes(30));
        try {
            executor.submit(() -> execute(pipe, executionId, executionId, null, 1, PipeExecutionTrigger.MANUAL,
                    triggeredBy, deadline, true));
        } catch (RuntimeException exception) {
            leave(pipeId);
            throw exception;
        }
        eventLogger.success("PIPE_EXECUTION_TRIGGERED", Map.of("pipeId", pipeId, "executionId", executionId, "trigger", "MANUAL"));
        return executionId;
    }

    public PipeHookExecution executeHook(long pipeId, Duration busyWaitTimeout, String triggeredBy, Runnable waitingCallback, Runnable acquiredCallback) {
        if (maintenanceModeService.isActive())
            return new PipeHookExecution(null, null, false, false, true);

        Pipe pipe = persistence.definition(pipeId);
        if (!pipe.isEnabled())
            return new PipeHookExecution(null, null, true, false, false);

        PipeGate gate = gates.computeIfAbsent(pipeId, _ -> new PipeGate());
        boolean acquired;
        try {
            acquired = gate.awaitTurn(pipe.isConcurrentExecutionAllowed(), busyWaitTimeout, waitingCallback);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            cleanup(pipeId, gate);
            return new PipeHookExecution(null, PipeOutcome.ERROR, false, false, false);
        }
        if (!acquired) {
            cleanup(pipeId, gate);
            return new PipeHookExecution(null, PipeOutcome.ERROR, false, true, false);
        }
        try {
            acquiredCallback.run();
        } catch (RuntimeException ignored) {
        }
        UUID executionId = UUID.randomUUID();
        try {
            PipeRun result = execute(pipe, executionId, executionId, null, 1, PipeExecutionTrigger.HOOK,
                    triggeredBy, Instant.now().plus(Duration.ofMinutes(30)), false);
            return new PipeHookExecution(executionId, result.outcome(), false, false, false);
        } catch (RuntimeException exception) {
            return new PipeHookExecution(executionId, PipeOutcome.ERROR, false, false, false);
        }
    }

    public InvokePipeResponse invoke(PipeInvocationTokenService.Claims claims) {
        UUID executionId = UUID.randomUUID();
        try {
            Pipe pipe = persistence.definition(claims.pipeId());
            if (!pipe.isEnabled())
                return failure(PipeInvocationFailureKind.PIPE_INVOCATION_FAILURE_KIND_DISABLED, null,
                        "Pipe '" + pipe.getName() + "' is disabled");
            if (!enter(pipe.getId(), pipe.isConcurrentExecutionAllowed()))
                return failure(PipeInvocationFailureKind.PIPE_INVOCATION_FAILURE_KIND_BUSY, null,
                        "Pipe '" + pipe.getName() + "' is already running");

            PipeRun result = execute(pipe, executionId, claims.rootExecutionId(), claims.parentProcedureExecutionId(),
                    claims.depth(), PipeExecutionTrigger.PROCEDURE, null, claims.deadline(), false);
            return InvokePipeResponse.newBuilder().setResult(PipeInvocationResult.newBuilder()
                    .setExecutionId(executionId.toString()).setResultJson(jsonMapper.writeValueAsString(result.summary()))).build();
        } catch (ProcedureDepthExceededException exception) {
            return failure(PipeInvocationFailureKind.PIPE_INVOCATION_FAILURE_KIND_DEPTH_EXCEEDED, null, exception.getMessage());
        } catch (RuntimeException exception) {
            UUID persisted = exception instanceof PipeExecutionException pipeException ? pipeException.getExecutionId() : executionId;
            return failure(PipeInvocationFailureKind.PIPE_INVOCATION_FAILURE_KIND_ERROR, persisted,
                    exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
        }
    }

    private PipeRun execute(Pipe pipe, UUID executionId, UUID rootExecutionId, UUID parentProcedureExecutionId, int depth, PipeExecutionTrigger trigger, String triggeredBy, Instant deadline, boolean includeDisabled) {
        List<ArtifactLocation> artifactsToDelete = new ArrayList<>();
        Map<ArtifactKey, ArtifactLocation> outputs = new LinkedHashMap<>();
        PipeOutcome aggregate = PipeOutcome.SUCCESS;
        boolean partial = false;
        boolean started = false;
        try {
            if (!includeDisabled && !pipe.isEnabled())
                throw new PipeExecutionException("Pipe '" + pipe.getName() + "' is disabled");
            if (depth > properties.maxDepth())
                throw new ProcedureDepthExceededException("Procedure-Pipe invocation depth exceeds " + properties.maxDepth());

            persistence.start(executionId, pipe, trigger, rootExecutionId, parentProcedureExecutionId, depth, triggeredBy);
            started = true;
            boolean continueSequence = true;
            UUID affinity = null;
            for (PipeStep step : pipe.getSteps()) {
                if (!continueSequence) {
                    persistence.completeStep(executionId, step.getStepKey(), PipeStepStatus.SKIPPED_SEQUENCE, null, null, null);
                    partial = true;
                    continue;
                }
                boolean enabled = step.getStepType() == PipeStepType.ALERT ? step.getAlert().isEnabled() : step.getProcedure().isEnabled();
                if (!enabled) {
                    persistence.completeStep(executionId, step.getStepKey(), PipeStepStatus.SKIPPED_DISABLED, null, null, null);
                    partial = true;
                    continue;
                }

                persistence.startStep(executionId, step.getStepKey());
                StepRun run;
                if (step.getStepType() == PipeStepType.ALERT) {
                    run = executeAlert(step, executionId, deadline);
                } else {
                    Map<String, ArtifactLocation> inputs = new LinkedHashMap<>();
                    for (var binding : step.getBindings()) {
                        ArtifactLocation artifact = outputs.get(new ArtifactKey(binding.getSourceStep().getStepKey(), binding.getSourceOutput().getOutputKey()));
                        if (artifact == null) {
                            persistence.completeStep(executionId, step.getStepKey(), PipeStepStatus.MISSING_PIPE_OUTPUT,
                                    PipeOutcome.ERROR, null, "MISSING_PIPE_OUTPUT");
                            return finish(executionId, PipeExecutionStatus.FAILED, PipeOutcome.ERROR,
                                    "MISSING_PIPE_OUTPUT", partial, outputs);
                        }
                        inputs.put(binding.getTargetParameter().getParameterKey(), artifact);
                    }
                    try {
                        Instant stepDeadline = earlier(deadline, Instant.now().plusMillis(step.getTimeoutMillis()));
                        var procedure = procedureOrchestrator.executePipeStep(step.getProcedure().getId(), rootExecutionId,
                                executionId, depth + 1, stepDeadline, preferred(inputs, affinity), inputs);
                        affinity = procedure.workerInstanceId();
                        artifactsToDelete.addAll(procedure.temporaryArtifacts());
                        artifactsToDelete.addAll(procedure.outputs());
                        for (ArtifactLocation output : procedure.outputs())
                            outputs.put(new ArtifactKey(step.getStepKey(), output.descriptor().getOutputKey()), output);
                        run = new StepRun(PipeOutcome.SUCCESS, PipeStepStatus.SUCCESS, procedure.executionId(), null);
                    } catch (ProcedureDisabledException exception) {
                        run = new StepRun(null, PipeStepStatus.SKIPPED_DISABLED, null, null);
                    } catch (ProcedureExecutionException exception) {
                        String code = exception.getMessage() != null && exception.getMessage().contains("Artifact")
                                ? "ARTIFACT_UNAVAILABLE" : "PROCEDURE_ERROR";
                        PipeStepStatus status = "ARTIFACT_UNAVAILABLE".equals(code)
                                ? PipeStepStatus.ARTIFACT_UNAVAILABLE : PipeStepStatus.ERROR;
                        run = new StepRun(PipeOutcome.ERROR, status, exception.getExecutionId(), code);
                    }
                }
                persistence.completeStep(executionId, step.getStepKey(), run.status(), run.outcome(), run.executionId(), run.errorCode());
                if (run.outcome() == PipeOutcome.ERROR)
                    aggregate = PipeOutcome.ERROR;
                else if (run.outcome() == PipeOutcome.WARN && aggregate == PipeOutcome.SUCCESS)
                    aggregate = PipeOutcome.WARN;
                if (run.outcome() == null || run.outcome() != PipeOutcome.SUCCESS)
                    partial = true;
                continueSequence = run.outcome() == null || step.getContinueOn().contains(run.outcome().name());
            }
            PipeExecutionStatus status = aggregate == PipeOutcome.ERROR ? PipeExecutionStatus.FAILED
                    : partial || aggregate == PipeOutcome.WARN ? PipeExecutionStatus.PARTIAL : PipeExecutionStatus.COMPLETED;
            return finish(executionId, status, aggregate, null, partial, outputs);
        } catch (RuntimeException exception) {
            if (started)
                persistence.failRunning(executionId, "PIPE_EXECUTION_ERROR");
            throw new PipeExecutionException(started ? executionId : null,
                    exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage(), exception);
        } finally {
            procedureOrchestrator.deleteArtifacts(artifactsToDelete);
            leave(pipe.getId());
        }
    }

    private StepRun executeAlert(PipeStep step, UUID pipeExecutionId, Instant deadline) {
        Duration timeout = Duration.ofMillis(step.getTimeoutMillis());
        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.compareTo(timeout) < 0)
            timeout = remaining;
        var execution = alertOrchestrator.executeHook(step.getAlert().getId(), step.getAlert().getName(),
                step.getAlert().isConcurrentExecutionAllowed(), timeout, "pipe:" + pipeExecutionId, () -> { }, () -> { });
        if (execution.busyTimeout())
            return new StepRun(PipeOutcome.ERROR, PipeStepStatus.ERROR, null, "ALERT_BUSY_TIMEOUT");
        if (execution.disabled())
            return new StepRun(null, PipeStepStatus.SKIPPED_DISABLED, null, null);
        if (execution.maintenance())
            return new StepRun(PipeOutcome.ERROR, PipeStepStatus.ERROR, null, "MAINTENANCE_MODE");

        PipeOutcome outcome = switch (execution.status()) {
            case SUCCESS -> PipeOutcome.SUCCESS;
            case WARN -> PipeOutcome.WARN;
            case ERROR -> PipeOutcome.ERROR;
        };
        return new StepRun(outcome, PipeStepStatus.valueOf(outcome.name()), execution.executionId(), null);
    }

    private PipeRun finish(UUID executionId, PipeExecutionStatus status, PipeOutcome outcome, String errorCode, boolean partial, Map<ArtifactKey, ArtifactLocation> outputs) {
        persistence.finish(executionId, status, outcome, errorCode);
        JsonNode summary = jsonMapper.valueToTree(Map.of("executionId", executionId.toString(), "status", status.name(),
                "outcome", outcome.name(), "partial", partial, "outputCount", outputs.size()));
        return new PipeRun(outcome, summary);
    }

    private static UUID preferred(Map<String, ArtifactLocation> inputs, UUID affinity) {
        if (inputs.isEmpty())
            return affinity;
        UUID candidate = null;
        for (ArtifactLocation location : inputs.values()) {
            if (candidate == null)
                candidate = location.workerInstanceId();
            else if (!candidate.equals(location.workerInstanceId()))
                return affinity;
        }
        return candidate;
    }

    private static Instant earlier(Instant left, Instant right) { return left.isBefore(right) ? left : right; }

    private static InvokePipeResponse failure(PipeInvocationFailureKind kind, UUID executionId, String message) {
        PipeInvocationFailure.Builder failure = PipeInvocationFailure.newBuilder().setKind(kind).setMessage(message);
        if (executionId != null)
            failure.setExecutionId(executionId.toString());

        return InvokePipeResponse.newBuilder().setFailure(failure).build();
    }

    private boolean enter(long pipeId, boolean concurrent) {
        PipeGate gate = gates.computeIfAbsent(pipeId, _ -> new PipeGate());
        boolean result = gate.tryEnter(concurrent);
        if (!result)
            cleanup(pipeId, gate);

        return result;
    }

    private void leave(long pipeId) {
        PipeGate gate = gates.get(pipeId);
        if (gate == null)
            return;

        gate.leave();
        cleanup(pipeId, gate);
    }

    private void cleanup(long pipeId, PipeGate gate) {
        if (gate.isIdle())
            gates.remove(pipeId, gate);
    }

    public boolean isRunning(long pipeId) {
        PipeGate gate = gates.get(pipeId);
        return gate != null && gate.isActive();
    }

    @Override
    public void close() { executor.close(); }

    public record PipeHookExecution(UUID executionId, PipeOutcome outcome, boolean disabled, boolean busyTimeout, boolean maintenance) { }
    private record PipeRun(PipeOutcome outcome, JsonNode summary) { }
    private record StepRun(PipeOutcome outcome, PipeStepStatus status, UUID executionId, String errorCode) { }
    private record ArtifactKey(String stepKey, String outputKey) { }
}
