package app.alertify.procedures.execution;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.stereotype.Service;

import app.alertify.alerts.execution.MaintenanceModeService;
import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.api.error.MaintenanceModeActiveException;
import app.alertify.grpc.AlertWorkerClient;
import app.alertify.grpc.WorkerGrpcProperties;
import app.alertify.grpc.WorkerTemplateSynchronizationException;
import app.alertify.grpc.discovery.SelectedWorker;
import app.alertify.grpc.discovery.WorkerEndpoint;
import app.alertify.grpc.discovery.WorkerReservation;
import app.alertify.grpc.discovery.WorkerStatusService;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.system.SystemStatusTickerPublisher;
import app.alertify.procedures.ProcedureBusyException;
import app.alertify.procedures.ProcedureDisabledException;
import app.alertify.procedures.ProcedureExecutionException;
import app.alertify.worker.grpc.AlertParameter;
import app.alertify.worker.grpc.AlertParameterValueSource;
import app.alertify.worker.grpc.ExecuteProcedureRequest;
import app.alertify.worker.grpc.ExecutionError;
import app.alertify.worker.grpc.InvokeProcedureResponse;
import app.alertify.worker.grpc.ProcedureExecutionResult;
import app.alertify.worker.grpc.ProcedureInvocationFailure;
import app.alertify.worker.grpc.ProcedureInvocationFailureKind;
import app.alertify.worker.grpc.ProcedureInvocationResult;
import app.alertify.worker.grpc.ProcedureParentKind;
import app.alertify.worker.grpc.SynchronizeTemplateRequest;
import app.alertify.worker.grpc.TemplateKind;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drives one procedure execution end to end: it prepares the procedure,
 * persists the RUNNING row, reserves a worker, opens an execution stream,
 * supplies the template source when the worker does not have it, and finalizes the row
 * with the outcome.
 *
 * <p>Manual and cron runs are submitted asynchronously and start a new
 * execution tree, while an invocation coming from a worker handle runs
 * synchronously on a stream callback virtual thread and inherits the root
 * execution, the parent, the depth and the deadline of its caller.
 *
 * <p>Secret values resolved for the procedure are stripped from any worker
 * error message and stack trace before they are persisted or returned.
 */
@Service
public class ProcedureExecutionOrchestrator implements AutoCloseable {
    private final ProcedureExecutionPreparationService preparationService;
    private final ProcedureExecutionPersistenceService persistenceService;
    private final ProcedureInvocationTokenService tokenService;
    private final ProcedureInvocationRegistry invocationRegistry;
    private final WorkerStatusService workerStatusService;
    private final AlertWorkerClient workerClient;
    private final WorkerGrpcProperties properties;
    private final ApplicationEventLogger eventLogger;
    private final JsonMapper jsonMapper;
    private final MaintenanceModeService maintenanceModeService;
    private final SystemStatusTickerPublisher statusTickerPublisher;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentMap<Long, ProcedureGate> procedureGates = new ConcurrentHashMap<>();

    public ProcedureExecutionOrchestrator(ProcedureExecutionPreparationService preparationService, ProcedureExecutionPersistenceService persistenceService, ProcedureInvocationTokenService tokenService, ProcedureInvocationRegistry invocationRegistry, WorkerStatusService workerStatusService, AlertWorkerClient workerClient, WorkerGrpcProperties properties, ApplicationEventLogger eventLogger, JsonMapper jsonMapper, MaintenanceModeService maintenanceModeService, SystemStatusTickerPublisher statusTickerPublisher) {
        this.preparationService = preparationService;
        this.persistenceService = persistenceService;
        this.tokenService = tokenService;
        this.invocationRegistry = invocationRegistry;
        this.workerStatusService = workerStatusService;
        this.workerClient = workerClient;
        this.properties = properties;
        this.eventLogger = eventLogger;
        this.jsonMapper = jsonMapper;
        this.maintenanceModeService = maintenanceModeService;
        this.statusTickerPublisher = statusTickerPublisher;
    }

    public boolean triggerManual(long procedureId, String procedureName, boolean allowConcurrentExecutions, String triggeredBy) {
        maintenanceModeService.assertNotActive();
        return triggerAsync(procedureId, procedureName, allowConcurrentExecutions, ProcedureExecutionTrigger.MANUAL, triggeredBy, true);
    }

    public void triggerCron(long procedureId, String procedureName, boolean allowConcurrentExecutions) {
        if (maintenanceModeService.isActive())
            return;

        triggerAsync(procedureId, procedureName, allowConcurrentExecutions, ProcedureExecutionTrigger.CRON, null, false);
    }

    private boolean triggerAsync(long procedureId, String procedureName, boolean allowConcurrentExecutions, ProcedureExecutionTrigger trigger, String triggeredBy, boolean includeDisabled) {
        if (!enter(procedureId, allowConcurrentExecutions)) {
            eventLogger.failure("PROCEDURE_EXECUTION_REJECTED", rejectionData(procedureId, procedureName, trigger, triggeredBy));
            return false;
        }

        try {
            UUID executionId = UUID.randomUUID();
            Instant deadline = Instant.now().plus(properties.execution().timeout());
            eventLogger.success("PROCEDURE_EXECUTION_TRIGGERED", data(procedureId, procedureName, executionId, trigger, triggeredBy));
            executor.submit(() -> {
                try {
                    execute(procedureId, executionId, executionId, null, null, 1, trigger, triggeredBy, deadline, includeDisabled, null);
                } catch (RuntimeException ignored) {
                    // Failure is persisted and audited by execute.
                }
            });
        } catch (RuntimeException exception) {
            leave(procedureId);
            throw exception;
        }
        return true;
    }

    public ProcedureHookExecution executeHook(long procedureId, String procedureName, boolean allowConcurrentExecutions, Duration busyWaitTimeout, String triggeredBy, Runnable waitingCallback, Runnable acquiredCallback) {
        if (maintenanceModeService.isActive())
            return new ProcedureHookExecution(null, false, false, false, true);

        ProcedureGate gate = procedureGates.computeIfAbsent(procedureId, _ -> new ProcedureGate());
        boolean acquired;
        try {
            acquired = gate.awaitTurn(allowConcurrentExecutions, busyWaitTimeout, waitingCallback);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            cleanup(procedureId, gate);
            return new ProcedureHookExecution(null, false, false, false, false);
        }
        if (!acquired) {
            cleanup(procedureId, gate);
            return new ProcedureHookExecution(null, false, false, true, false);
        }

        boolean executionStarted = false;
        UUID executionId = null;
        try {
            runCallback(acquiredCallback);
            executionId = UUID.randomUUID();
            Instant deadline = Instant.now().plus(properties.execution().timeout());
            eventLogger.success("PROCEDURE_EXECUTION_TRIGGERED", data(procedureId, procedureName, executionId, ProcedureExecutionTrigger.HOOK, triggeredBy));
            executionStarted = true;
            execute(procedureId, executionId, executionId, null, null, 1,
                    ProcedureExecutionTrigger.HOOK, triggeredBy, deadline, false, null);
            return new ProcedureHookExecution(executionId, true, false, false, false);
        } catch (ProcedureDisabledException exception) {
            return new ProcedureHookExecution(null, false, true, false, false);
        } catch (RuntimeException exception) {
            UUID persistedId = exception instanceof ProcedureExecutionException executionException
                    ? executionException.getExecutionId() : executionId;
            return new ProcedureHookExecution(persistedId, false, false, false, false);
        } finally {
            if (!executionStarted)
                leave(procedureId);
        }
    }

    public record ProcedureHookExecution(UUID executionId, boolean successful, boolean disabled, boolean busyTimeout, boolean maintenance) { }

    public InvokeProcedureResponse invoke(ProcedureInvocationTokenService.Claims claims) {
        if (maintenanceModeService.isActive())
            return failure(ProcedureInvocationFailureKind.PROCEDURE_INVOCATION_FAILURE_KIND_DISABLED, null,
                    new MaintenanceModeActiveException("The system is in maintenance mode and is not accepting new executions"), null);

        UUID executionId = UUID.randomUUID();
        ProcedureExecutionTrigger trigger = claims.parentKind() == ProcedureParentKind.PROCEDURE_PARENT_KIND_ALERT
                ? ProcedureExecutionTrigger.ALERT : ProcedureExecutionTrigger.PROCEDURE;
        UUID parentAlert = trigger == ProcedureExecutionTrigger.ALERT ? claims.parentExecutionId() : null;
        UUID parentProcedure = trigger == ProcedureExecutionTrigger.PROCEDURE ? claims.parentExecutionId() : null;
        try {
            PreparedProcedureExecution prepared = preparationService.prepare(claims.procedureId(), false);
            if (!enter(claims.procedureId(), prepared.allowConcurrentExecutions())) {
                ProcedureBusyException exception = busy(prepared.procedureName());
                eventLogger.failure("PROCEDURE_EXECUTION_REJECTED", rejectionData(claims.procedureId(), prepared.procedureName(), trigger, null));
                return failure(ProcedureInvocationFailureKind.PROCEDURE_INVOCATION_FAILURE_KIND_BUSY, null, exception, null);
            }

            JsonNode result = execute(claims.procedureId(), executionId, claims.rootExecutionId(),
                    parentAlert, parentProcedure, claims.depth(), trigger, null, claims.deadline(), false, prepared);
            return InvokeProcedureResponse.newBuilder().setResult(ProcedureInvocationResult.newBuilder()
                    .setExecutionId(executionId.toString())
                    .setResultJson(jsonMapper.writeValueAsString(result))).build();
        } catch (ProcedureDisabledException exception) {
            return failure(ProcedureInvocationFailureKind.PROCEDURE_INVOCATION_FAILURE_KIND_DISABLED,
                    null, exception, null);
        } catch (ProcedureExecutionException exception) {
            return failure(ProcedureInvocationFailureKind.PROCEDURE_INVOCATION_FAILURE_KIND_ERROR,
                    exception.getExecutionId(), exception, null);
        } catch (RuntimeException exception) {
            return failure(ProcedureInvocationFailureKind.PROCEDURE_INVOCATION_FAILURE_KIND_ERROR,
                    executionId, exception, null);
        }
    }

    public InvokeProcedureResponse invokeToken(String token) {
        try {
            return invoke(tokenService.validate(token));
        } catch (app.alertify.procedures.ProcedureDepthExceededException exception) {
            return failure(ProcedureInvocationFailureKind.PROCEDURE_INVOCATION_FAILURE_KIND_DEPTH_EXCEEDED, null, exception, null);
        } catch (ProcedureExecutionException exception) {
            return failure(ProcedureInvocationFailureKind.PROCEDURE_INVOCATION_FAILURE_KIND_ERROR, exception.getExecutionId(), exception, null);
        }
    }

    private JsonNode execute(long procedureId, UUID executionId, UUID rootExecutionId, UUID parentAlertExecutionId, UUID parentProcedureExecutionId, int depth, ProcedureExecutionTrigger trigger, String triggeredBy, Instant deadline, boolean includeDisabled, PreparedProcedureExecution preparedExecution) {
        Instant startedAt = Instant.now();
        boolean started = false;
        WorkerEndpoint endpoint = null;
        try {
            PreparedProcedureExecution prepared;
            try {
                prepared = preparedExecution == null ? preparationService.prepare(procedureId, includeDisabled) : preparedExecution;
            } catch (ProcedureDisabledException exception) {
                eventLogger.failure("PROCEDURE_EXECUTION_REJECTED", Map.of("procedureId", procedureId, "reason", "DISABLED", "parentExecutionId", parentAlertExecutionId != null ? parentAlertExecutionId : parentProcedureExecutionId));
                throw exception;
            }
            persistenceService.start(executionId, prepared, trigger, rootExecutionId,
                    parentAlertExecutionId, parentProcedureExecutionId, depth, startedAt, triggeredBy);
            started = true;
            invocationRegistry.register(executionId, deadline);
            try (WorkerReservation reservation = workerStatusService.reserve(prepared.requiredCapability())) {
                SelectedWorker worker = reservation.worker();
                endpoint = worker.endpoint();
                Map<String, Object> startedData = data(procedureId, prepared.procedureName(), executionId, trigger, triggeredBy);
                startedData.put("worker", endpoint.toString());
                startedData.put("depth", depth);
                eventLogger.success("PROCEDURE_EXECUTION_STARTED", startedData);
                statusTickerPublisher.publish();
                ExecuteProcedureRequest request = request(executionId, rootExecutionId, parentAlertExecutionId, parentProcedureExecutionId, depth, deadline, prepared);
                SynchronizeTemplateRequest source = SynchronizeTemplateRequest.newBuilder().setTemplateClassName(prepared.templateClassName()).setSourceChecksum(prepared.sourceChecksum()).setSource(prepared.source()).setTemplateKind(TemplateKind.TEMPLATE_KIND_PROCEDURE).build();
                ProcedureExecutionResult result;
                try {
                    result = workerClient.executeProcedure(endpoint, request, source, remaining(deadline), this::invokeToken);
                } catch (WorkerTemplateSynchronizationException exception) {
                    ExecutionError sanitized = sanitize(exception.error(), prepared);
                    Instant finishedAt = Instant.now();
                    persistenceService.failRemote(executionId, endpoint, worker.status().getWorkerName(), worker.status().getWorkerInstanceId(), finishedAt, finishedAt, sanitized);
                    throw new ProcedureExecutionException(executionId, message(sanitized));
                }
                if (!result.getSuccessful()) {
                    result = result.toBuilder().setError(sanitize(result.getError(), prepared)).build();
                }
                JsonNode value = persistenceService.complete(executionId, endpoint, result, prepared.sensitiveResult());
                if (!result.getSuccessful())
                    throw new ProcedureExecutionException(executionId, message(result.getError()));

                return value;
            }
        } catch (ProcedureDisabledException exception) {
            throw exception;
        } catch (ProcedureExecutionException exception) {
            // Some ProcedureExecutionException paths (for example an exhausted inherited
            // deadline) happen before a worker result has finalized the RUNNING row.
            // failLocal is idempotent and leaves already-completed/error rows untouched.
            if (started)
                persistenceService.failLocal(executionId, exception);

            throw exception;
        } catch (Throwable exception) {
            if (exception instanceof InterruptedException)
                Thread.currentThread().interrupt();

            if (started)
                persistenceService.failLocal(executionId, exception);

            throw new ProcedureExecutionException(started ? executionId : null,
                    exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage(), exception);
        } finally {
            invocationRegistry.unregister(executionId);
            leave(procedureId);
            statusTickerPublisher.publish();
        }
    }

    private ExecuteProcedureRequest request(UUID executionId, UUID rootExecutionId, UUID parentAlertExecutionId, UUID parentProcedureExecutionId, int depth, Instant deadline, PreparedProcedureExecution prepared) {
        ExecuteProcedureRequest.Builder request = ExecuteProcedureRequest.newBuilder()
                .setExecutionId(executionId.toString())
                .setProcedureId(prepared.procedureId())
                .setProcedureVersion(prepared.procedureVersion())
                .setProcedureName(prepared.procedureName())
                .setTemplateClassName(prepared.templateClassName())
                .setSourceChecksum(prepared.sourceChecksum())
                .setRootExecutionId(rootExecutionId.toString())
                .setParentExecutionId((parentAlertExecutionId != null
                        ? parentAlertExecutionId : parentProcedureExecutionId) == null
                                ? "" : (parentAlertExecutionId != null
                                        ? parentAlertExecutionId : parentProcedureExecutionId).toString())
                .setParentKind(parentAlertExecutionId != null
                        ? ProcedureParentKind.PROCEDURE_PARENT_KIND_ALERT
                        : parentProcedureExecutionId != null
                                ? ProcedureParentKind.PROCEDURE_PARENT_KIND_PROCEDURE
                                : ProcedureParentKind.PROCEDURE_PARENT_KIND_UNSPECIFIED)
                .setDepth(depth);
        for (ResolvedProcedureParameter parameter : prepared.parameters())
            request.addParameters(parameter(parameter, rootExecutionId, executionId,
                    ProcedureParentKind.PROCEDURE_PARENT_KIND_PROCEDURE, depth + 1, deadline));

        return request.build();
    }

    public AlertParameter parameter(ResolvedProcedureParameter parameter, UUID rootExecutionId, UUID parentExecutionId, ProcedureParentKind parentKind, int childDepth, Instant deadline) {
        AlertParameter.Builder value = AlertParameter.newBuilder().setName(parameter.name())
                .setJavaType(parameter.javaType())
                .setNullValue(parameter.source() != AlertParameterSource.PROCEDURE && parameter.nullValue())
                .setSource(source(parameter.source()));
        if (parameter.source() == AlertParameterSource.PROCEDURE) {
            value.setProcedureId(parameter.procedureId())
                    .setInvocationToken(tokenService.issue(parameter.procedureId(), rootExecutionId,
                            parentExecutionId, parentKind, childDepth, deadline));
        } else if (!parameter.nullValue()) {
            value.setValue(parameter.value());
        }
        if (parameter.writable()) {
            value.setWritable(true);
            if (parameter.configurationId() != null)
                value.setConfigurationId(parameter.configurationId());
            else if (parameter.secretId() != null)
                value.setSecretId(parameter.secretId());
        }
        return value.build();
    }

    private static AlertParameterValueSource source(AlertParameterSource source) {
        return switch (source) {
            case TEXT -> AlertParameterValueSource.ALERT_PARAMETER_VALUE_SOURCE_TEXT;
            case CONFIGURATION -> AlertParameterValueSource.ALERT_PARAMETER_VALUE_SOURCE_CONFIGURATION;
            case SECRET -> AlertParameterValueSource.ALERT_PARAMETER_VALUE_SOURCE_SECRET;
            case PROCEDURE -> AlertParameterValueSource.ALERT_PARAMETER_VALUE_SOURCE_PROCEDURE;
        };
    }

    private static Duration remaining(Instant deadline) {
        Duration value = Duration.between(Instant.now(), deadline);
        if (value.isZero() || value.isNegative())
            throw new ProcedureExecutionException("Procedure invocation deadline has expired");

        return value;
    }

    private static ExecutionError sanitize(ExecutionError error, PreparedProcedureExecution prepared) {
        String message = error.getMessage();
        String stack = error.getStackTrace();
        for (ResolvedProcedureParameter parameter : prepared.parameters()) {
            if (parameter.source() != AlertParameterSource.SECRET || parameter.value() == null || parameter.value().isEmpty())
                continue;

            message = message.replace(parameter.value(), "[REDACTED]");
            stack = stack.replace(parameter.value(), "[REDACTED]");
        }
        return error.toBuilder().setMessage(message).setStackTrace(stack).build();
    }

    private static InvokeProcedureResponse failure(ProcedureInvocationFailureKind kind, UUID executionId, Throwable exception, ExecutionError error) {
        ProcedureInvocationFailure.Builder failure = ProcedureInvocationFailure.newBuilder().setKind(kind)
                .setMessage(exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
        if (executionId != null)
            failure.setExecutionId(executionId.toString());

        if (error != null)
            failure.setError(error);

        return InvokeProcedureResponse.newBuilder().setFailure(failure).build();
    }

    private static String message(ExecutionError error) {
        return error.getMessage().isBlank() ? error.getType() : error.getMessage();
    }

    private static Map<String, Object> data(long procedureId, String name, UUID executionId, ProcedureExecutionTrigger trigger, String triggeredBy) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("procedureId", procedureId);
        data.put("procedureName", name);
        data.put("executionId", executionId);
        data.put("trigger", trigger.name());
        data.put("triggeredBy", triggeredBy == null ? "system" : triggeredBy);
        return data;
    }

    private boolean enter(long procedureId, boolean concurrent) {
        ProcedureGate gate = procedureGates.computeIfAbsent(procedureId, _ -> new ProcedureGate());
        boolean entered = gate.tryEnter(concurrent);
        if (!entered)
            cleanup(procedureId, gate);

        return entered;
    }

    private void leave(long procedureId) {
        ProcedureGate gate = procedureGates.get(procedureId);
        if (gate == null)
            return;

        gate.leave();
        cleanup(procedureId, gate);
    }

    public boolean isRunning(long procedureId) {
        ProcedureGate gate = procedureGates.get(procedureId);
        return gate != null && gate.isActive();
    }

    private void cleanup(long procedureId, ProcedureGate gate) {
        if (gate.isIdle())
            procedureGates.remove(procedureId, gate);
    }

    private static void runCallback(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException ignored) {
            // Invocation state can be reconciled; callback failures must never leak a Procedure permit.
        }
    }

    private static Map<String, Object> rejectionData(long procedureId, String procedureName, ProcedureExecutionTrigger trigger, String triggeredBy) {
        Map<String, Object> data = data(procedureId, procedureName, null, trigger, triggeredBy);
        data.put("reason", "ALREADY_RUNNING");
        return data;
    }

    private static ProcedureBusyException busy(String procedureName) {
        return new ProcedureBusyException("Procedure '" + procedureName + "' is already running and does not allow concurrent executions");
    }

    @Override
    public void close() { executor.close(); }
}
