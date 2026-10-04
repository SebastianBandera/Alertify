package app.alertify.alerts.execution;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.alertify.alerts.model.Alert;
import app.alertify.alerts.model.AlertExecution;
import app.alertify.alerts.model.AlertExecutionWorker;
import app.alertify.alerts.model.AlertState;
import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.grpc.discovery.WorkerEndpoint;
import app.alertify.configuration.service.WritableConfigurationService;
import app.alertify.execution.ExecutionTimestamps;
import app.alertify.jpa.repository.AlertExecutionRepository;
import app.alertify.jpa.repository.AlertRepository;
import app.alertify.jpa.repository.AlertStateRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.services.secret.WritableSecretService;
import app.alertify.worker.contract.SecretValueSanitizer;
import app.alertify.worker.grpc.AlertExecutionResult;
import app.alertify.worker.grpc.ExecutionError;
import app.alertify.worker.grpc.WorkerExecutionStatus;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class AlertExecutionPersistenceService {

    private final AlertRepository alertRepository;
    private final AlertExecutionRepository executionRepository;
    private final AlertStateRepository stateRepository;
    private final ApplicationEventLogger eventLogger;
    private final WritableConfigurationService writableConfigurationService;
    private final WritableSecretService writableSecretService;
    private final JsonMapper jsonMapper;
    private final ConcurrentMap<UUID, TriggerContext> triggerContexts = new ConcurrentHashMap<>();

    public AlertExecutionPersistenceService(AlertRepository alertRepository, AlertExecutionRepository executionRepository, AlertStateRepository stateRepository, ApplicationEventLogger eventLogger, JsonMapper jsonMapper, WritableConfigurationService writableConfigurationService, WritableSecretService writableSecretService) {
        this.alertRepository = alertRepository;
        this.executionRepository = executionRepository;
        this.stateRepository = stateRepository;
        this.eventLogger = eventLogger;
        this.jsonMapper = jsonMapper;
        this.writableConfigurationService = writableConfigurationService;
        this.writableSecretService = writableSecretService;
    }

    public void registerTrigger(UUID executionId, AlertExecutionTrigger trigger, String triggeredBy) {
        triggerContexts.put(executionId, new TriggerContext(trigger, triggeredBy, null, null, null, null));
    }

    public void registerPipeParent(UUID executionId, String triggeredBy, UUID parentPipeExecutionId, String parentStepKey) {
        triggerContexts.put(executionId, new TriggerContext(AlertExecutionTrigger.PIPE, triggeredBy, parentPipeExecutionId, parentStepKey, null, null));
    }

    public void registerHookParent(UUID executionId, String triggeredBy, UUID parentHookInvocationId, String parentHookName) {
        triggerContexts.put(executionId, new TriggerContext(AlertExecutionTrigger.HOOK, triggeredBy, null, null, parentHookInvocationId, parentHookName));
    }

    public void clearTrigger(UUID executionId) { triggerContexts.remove(executionId); }

    @Transactional
    public void persistObserved(long alertId, UUID executionId, Instant startedAt, app.alertify.alerts.service.ResourceResultObserverService.ObservedResult result) {
        TriggerContext context = triggerContexts.get(executionId);
        AlertExecution execution = AlertExecution.observed(executionId, alert(alertId), result.status(), startedAt, Instant.now(), result.summary(), trigger(context), actor(context));
        recordParent(execution, context);
        executionRepository.save(execution);
        logResult(execution);
    }

    @Transactional
    public void persistWorkerResult(long alertId, UUID executionId, WorkerEndpoint endpoint, AlertExecutionResult result, PreparedAlertExecution prepared) {
        TriggerContext context = triggerContexts.get(executionId);
        Alert alert = alert(alertId);
        ExecutionTimestamps timestamps = ExecutionTimestamps.ordered("ALERT", executionId, result.getWorkerName(),
                instant(result.getStartedAt()), instant(result.getWorkStartedAt()), instant(result.getFinishedAt()));
        AlertExecution execution;
        AlertExecutionWorker worker = worker(endpoint, result.getWorkerName(), result.getWorkerInstanceId());

        if (result.getStatus() == WorkerExecutionStatus.WORKER_EXECUTION_STATUS_ERROR) {
            ExecutionError error = sanitize(result.getError(), prepared);
            execution = AlertExecution.error(
                    executionId, alert, worker, timestamps.startedAt(), timestamps.workStartedAt(), timestamps.finishedAt(),
                    required(error.getType(), "Worker error type"), emptyToNull(error.getMessage()),
                    emptyToNull(error.getStackTrace()), trigger(context), actor(context)
            );
        } else {
            execution = AlertExecution.result(
                    executionId, alert, worker, status(result.getStatus()), timestamps.startedAt(), timestamps.workStartedAt(), timestamps.finishedAt(),
                    statusMessage(result.getStatusMessageJson(), prepared), trigger(context), actor(context)
            );
        }

        recordParent(execution, context);
        executionRepository.save(execution);
        AlertState state = stateRepository.findById(alertId).orElseThrow(() -> new IllegalStateException("Alert state " + alertId + " was not found"));
        state.replaceState(SecretValueSanitizer.sanitize(result.getState(), secretValues(prepared)));
        stateRepository.save(state);
        if (result.getStatus() != WorkerExecutionStatus.WORKER_EXECUTION_STATUS_ERROR) {
            writableConfigurationService.apply(
                    alertId, alert.getName(), executionId,
                    result.getWritableConfigurationValuesList()
            );
            writableSecretService.apply(
                    alertId, alert.getName(), executionId,
                    result.getWritableSecretValuesList()
            );
        }
        logResult(execution);
    }

    @Transactional
    public void persistRemoteFailure(long alertId, UUID executionId, WorkerEndpoint endpoint, String workerName, String workerInstanceId, Instant startedAt, Instant workStartedAt, Instant finishedAt, ExecutionError error, PreparedAlertExecution prepared) {
        ExecutionError sanitized = sanitize(error, prepared);
        persistFailure(
                alertId, executionId, worker(endpoint, workerName, workerInstanceId), startedAt, workStartedAt, finishedAt,
                required(sanitized.getType(), "Worker error type"), emptyToNull(sanitized.getMessage()),
                emptyToNull(sanitized.getStackTrace())
        );
    }

    @Transactional
    public void persistLocalFailure(long alertId, UUID executionId, WorkerEndpoint endpoint, String workerName, String workerInstanceId, Instant startedAt, Throwable error, PreparedAlertExecution prepared) {
        Instant finishedAt = Instant.now();
        ExecutionError sanitized = sanitize(error(error), prepared);
        persistFailure(
                alertId, executionId, worker(endpoint, workerName, workerInstanceId), startedAt, startedAt, finishedAt,
                sanitized.getType(), emptyToNull(sanitized.getMessage()), emptyToNull(sanitized.getStackTrace())
        );
    }

    private void persistFailure(long alertId, UUID executionId, AlertExecutionWorker worker, Instant startedAt, Instant workStartedAt, Instant finishedAt, String errorType, String errorMessage, String errorStackTrace) {
        TriggerContext context = triggerContexts.get(executionId);
        ExecutionTimestamps timestamps = ExecutionTimestamps.ordered("ALERT", executionId, worker == null ? null : worker.name(), startedAt, workStartedAt, finishedAt);
        AlertExecution execution = AlertExecution.error(
                executionId, alert(alertId), worker, timestamps.startedAt(), timestamps.workStartedAt(), timestamps.finishedAt(),
                errorType, errorMessage, errorStackTrace, trigger(context), actor(context)
        );
        recordParent(execution, context);
        executionRepository.save(execution);
        logResult(execution);
    }

    private void logResult(AlertExecution execution) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("executionId", execution.getExecutionId());
        data.put("alertId", execution.getAlert().getId());
        data.put("alertName", execution.getAlert().getName());
        data.put("status", execution.getStatus().name());
        data.put("idleMillis", Duration.between(
                execution.getStartedAt(), execution.getWorkStartedAt()
        ).toMillis());
        data.put("executionMillis", Duration.between(
                execution.getWorkStartedAt(), execution.getFinishedAt()
        ).toMillis());
        if (execution.getWorkerInstanceId() != null) {
            data.put("worker", execution.getWorkerIpAddress() + ":" + execution.getWorkerPort());
            data.put("workerName", execution.getWorkerName());
            data.put("workerInstanceId", execution.getWorkerInstanceId());
        }
        if (execution.getStatus() == AlertExecutionStatus.ERROR) {
            data.put("errorType", execution.getErrorType());
            eventLogger.errorAfterCommit("ALERT_EXECUTION_COMPLETED", data);
        } else {
            eventLogger.successAfterCommit("ALERT_EXECUTION_COMPLETED", data);
        }
    }

    private Alert alert(long alertId) {
        return alertRepository.findById(alertId)
                .orElseThrow(() -> new IllegalStateException("Alert " + alertId + " was not found"));
    }

    private static AlertExecutionWorker worker(
        WorkerEndpoint endpoint,
        String workerName,
        String workerInstanceId
    ) {
        if (endpoint == null)
            return null;

        return new AlertExecutionWorker(
                required(workerName, "Worker name"), endpoint.ipAddress(), endpoint.port(),
                UUID.fromString(required(workerInstanceId, "Worker instance ID"))
        );
    }

    private JsonNode statusMessage(String json, PreparedAlertExecution prepared) {
        if (json == null || json.isBlank())
            return null;

        try {
            return SecretValueSanitizer.sanitize(jsonMapper.readTree(json), secretValues(prepared));
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Worker returned invalid status message JSON", exception);
        }
    }

    private static app.alertify.alerts.execution.AlertExecutionStatus status(WorkerExecutionStatus status) {
        return switch (status) {
            case WORKER_EXECUTION_STATUS_SUCCESS -> AlertExecutionStatus.SUCCESS;
            case WORKER_EXECUTION_STATUS_WARN -> AlertExecutionStatus.WARN;
            case WORKER_EXECUTION_STATUS_ERROR, WORKER_EXECUTION_STATUS_UNSPECIFIED,
                    UNRECOGNIZED -> throw new IllegalArgumentException(
                        "Worker returned invalid execution status " + status
                    );
        };
    }

    private static Instant instant(com.google.protobuf.Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    private static String stackTrace(Throwable error) {
        StringWriter stackTrace = new StringWriter();
        error.printStackTrace(new PrintWriter(stackTrace));
        return stackTrace.toString();
    }

    private static ExecutionError error(Throwable error) {
        return ExecutionError.newBuilder()
                .setType(error.getClass().getName())
                .setMessage(error.getMessage() == null ? "" : error.getMessage())
                .setStackTrace(stackTrace(error))
                .build();
    }

    private static ExecutionError sanitize(ExecutionError error, PreparedAlertExecution prepared) {
        return SecretValueSanitizer.sanitize(error, secretValues(prepared));
    }

    private static List<String> secretValues(PreparedAlertExecution prepared) {
        if (prepared == null)
            return List.of();

        List<String> values = new ArrayList<>(prepared.parameters().stream()
                .filter(parameter -> parameter.source() == AlertParameterSource.SECRET)
                .map(ResolvedAlertParameter::value)
                .filter(value -> value != null)
                .toList());
        prepared.preparedValues().stream()
                .filter(value -> value.source() == app.alertify.alerts.AlertExecutionValueSource.SECRET)
                .map(app.alertify.alerts.AlertExecutionValue::value)
                .filter(value -> value != null)
                .forEach(values::add);
        return List.copyOf(values);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(name + " must not be blank");

        return value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static AlertExecutionTrigger trigger(TriggerContext context) { return context == null ? null : context.trigger(); }
    private static String actor(TriggerContext context) { return context == null ? null : context.triggeredBy(); }
    private static void recordParent(AlertExecution execution, TriggerContext context) {
        if (context != null) {
            execution.recordPipeParent(context.parentPipeExecutionId(), context.parentStepKey());
            execution.recordHookParent(context.parentHookInvocationId(), context.parentHookName());
        }
    }

    private record TriggerContext(AlertExecutionTrigger trigger, String triggeredBy, UUID parentPipeExecutionId, String parentStepKey, UUID parentHookInvocationId, String parentHookName) { }
}
