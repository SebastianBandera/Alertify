package app.alertify.alerts.execution;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.alertify.alerts.model.Alert;
import app.alertify.alerts.model.AlertExecution;
import app.alertify.alerts.model.AlertExecutionWorker;
import app.alertify.alerts.model.AlertState;
import app.alertify.grpc.discovery.WorkerEndpoint;
import app.alertify.jpa.repository.AlertExecutionRepository;
import app.alertify.jpa.repository.AlertRepository;
import app.alertify.jpa.repository.AlertStateRepository;
import app.alertify.logging.ApplicationEventLogger;
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
    private final JsonMapper jsonMapper;

    public AlertExecutionPersistenceService(AlertRepository alertRepository, AlertExecutionRepository executionRepository, AlertStateRepository stateRepository, ApplicationEventLogger eventLogger, JsonMapper jsonMapper) {
        this.alertRepository = alertRepository;
        this.executionRepository = executionRepository;
        this.stateRepository = stateRepository;
        this.eventLogger = eventLogger;
        this.jsonMapper = jsonMapper;
    }

    @Transactional
    public void persistWorkerResult(long alertId, UUID executionId, WorkerEndpoint endpoint, AlertExecutionResult result) {
        Alert alert = alert(alertId);
        Instant startedAt = instant(result.getStartedAt());
        Instant workStartedAt = instant(result.getWorkStartedAt());
        Instant finishedAt = instant(result.getFinishedAt());
        AlertExecution execution;
        AlertExecutionWorker worker = worker(endpoint, result.getWorkerName(), result.getWorkerInstanceId());

        if (result.getStatus() == WorkerExecutionStatus.WORKER_EXECUTION_STATUS_ERROR) {
            ExecutionError error = result.getError();
            execution = AlertExecution.error(
                    executionId, alert, worker, startedAt, workStartedAt, finishedAt,
                    required(error.getType(), "Worker error type"), emptyToNull(error.getMessage()),
                    emptyToNull(error.getStackTrace())
            );
        } else {
            execution = AlertExecution.result(
                    executionId, alert, worker, status(result.getStatus()), startedAt, workStartedAt, finishedAt,
                    statusMessage(result.getStatusMessageJson())
            );
        }

        executionRepository.save(execution);
        AlertState state = stateRepository.findById(alertId).orElseThrow(() -> new IllegalStateException("Alert state " + alertId + " was not found"));
        state.replaceState(result.getState());
        stateRepository.save(state);
        logResult(execution);
    }

    @Transactional
    public void persistRemoteFailure(
        long alertId,
        UUID executionId,
        WorkerEndpoint endpoint,
        String workerName,
        String workerInstanceId,
        Instant startedAt,
        Instant workStartedAt,
        Instant finishedAt,
        ExecutionError error
    ) {
        persistFailure(
                alertId, executionId, worker(endpoint, workerName, workerInstanceId), startedAt, workStartedAt, finishedAt,
                required(error.getType(), "Worker error type"), emptyToNull(error.getMessage()),
                emptyToNull(error.getStackTrace())
        );
    }

    @Transactional
    public void persistLocalFailure(
        long alertId,
        UUID executionId,
        WorkerEndpoint endpoint,
        String workerName,
        String workerInstanceId,
        Instant startedAt,
        Throwable error
    ) {
        Instant finishedAt = Instant.now();
        persistFailure(
                alertId, executionId, worker(endpoint, workerName, workerInstanceId), startedAt, startedAt, finishedAt,
                error.getClass().getName(), error.getMessage(), stackTrace(error)
        );
    }

    private void persistFailure(long alertId, UUID executionId, AlertExecutionWorker worker, Instant startedAt, Instant workStartedAt, Instant finishedAt, String errorType, String errorMessage, String errorStackTrace) {
        AlertExecution execution = AlertExecution.error(
                executionId, alert(alertId), worker, startedAt, workStartedAt, finishedAt,
                errorType, errorMessage, errorStackTrace
        );
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
            if (execution.getErrorMessage() != null)
                data.put("errorMessage", execution.getErrorMessage());
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

    private JsonNode statusMessage(String json) {
        if (json == null || json.isBlank())
            return null;
        try {
            return jsonMapper.readTree(json);
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

    private static String required(String value, String name) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
