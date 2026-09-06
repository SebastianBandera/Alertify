package app.alertify.procedures.execution;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.alertify.alerts.model.AlertExecutionWorker;
import app.alertify.configuration.service.WritableConfigurationService;
import app.alertify.grpc.discovery.WorkerEndpoint;
import app.alertify.jpa.repository.ProcedureExecutionRepository;
import app.alertify.jpa.repository.ProcedureRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.procedures.model.Procedure;
import app.alertify.procedures.model.ProcedureExecution;
import app.alertify.services.secret.WritableSecretService;
import app.alertify.worker.grpc.ExecutionError;
import app.alertify.worker.grpc.ProcedureExecutionResult;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Owns the transactional lifecycle of a procedure execution row: it opens the
 * RUNNING row, finalizes it with the worker outcome and applies the writable
 * configuration and secret values the template reported.
 *
 * <p>Each method runs in its own transaction so an execution stays visible
 * while it is in progress and its outcome is committed even when the caller
 * fails afterwards. {@code failLocal} is the idempotent fallback for failures
 * detected on the backend side: it only touches a row that is still RUNNING, so
 * it can be called on paths where a worker result may already have finalized
 * the row.
 */
@Service
public class ProcedureExecutionPersistenceService {
    private final ProcedureRepository procedureRepository;
    private final ProcedureExecutionRepository executionRepository;
    private final WritableConfigurationService writableConfigurationService;
    private final WritableSecretService writableSecretService;
    private final ApplicationEventLogger eventLogger;
    private final JsonMapper jsonMapper;

    public ProcedureExecutionPersistenceService(ProcedureRepository procedureRepository,
            ProcedureExecutionRepository executionRepository,
            WritableConfigurationService writableConfigurationService,
            WritableSecretService writableSecretService, ApplicationEventLogger eventLogger,
            JsonMapper jsonMapper) {
        this.procedureRepository = procedureRepository;
        this.executionRepository = executionRepository;
        this.writableConfigurationService = writableConfigurationService;
        this.writableSecretService = writableSecretService;
        this.eventLogger = eventLogger;
        this.jsonMapper = jsonMapper;
    }

    @Transactional
    public void start(UUID executionId, PreparedProcedureExecution prepared, ProcedureExecutionTrigger trigger, UUID rootExecutionId, UUID parentAlertExecutionId, UUID parentProcedureExecutionId, int depth, Instant startedAt, String triggeredBy) {
        Procedure procedure = procedureRepository.findById(prepared.procedureId())
                .orElseThrow(() -> new IllegalStateException("Procedure " + prepared.procedureId() + " was not found"));
        executionRepository.saveAndFlush(ProcedureExecution.running(executionId, procedure,
                prepared.procedureVersion(), trigger, rootExecutionId, parentAlertExecutionId,
                parentProcedureExecutionId, depth, startedAt, triggeredBy));
    }

    @Transactional
    public JsonNode complete(UUID executionId, WorkerEndpoint endpoint, ProcedureExecutionResult result, boolean sensitiveResult) {
        ProcedureExecution execution = execution(executionId);
        AlertExecutionWorker worker = worker(endpoint, result.getWorkerName(), result.getWorkerInstanceId());
        if (!result.getSuccessful()) {
            ExecutionError error = result.getError();
            execution.fail(worker, instant(result.getWorkStartedAt()), instant(result.getFinishedAt()),
                    required(error.getType(), "Worker error type"), empty(error.getMessage()), empty(error.getStackTrace()));
            executionRepository.flush();
            log(execution);
            return null;
        }
        JsonNode value = parse(result.getResultJson());
        execution.complete(worker, instant(result.getWorkStartedAt()), instant(result.getFinishedAt()),
                value, sensitiveResult);
        executionRepository.flush();
        writableConfigurationService.applyProcedure(execution.getProcedure().getId(),
                execution.getProcedure().getName(), executionId, result.getWritableConfigurationValuesList());
        writableSecretService.applyProcedure(execution.getProcedure().getId(), execution.getProcedure().getName(),
                executionId, result.getWritableSecretValuesList());
        log(execution);
        return value;
    }

    @Transactional
    public void failRemote(UUID executionId, WorkerEndpoint endpoint, String workerName, String workerInstanceId, Instant workStartedAt, Instant finishedAt, ExecutionError error) {
        ProcedureExecution execution = execution(executionId);
        execution.fail(worker(endpoint, workerName, workerInstanceId), workStartedAt, finishedAt,
                required(error.getType(), "Worker error type"), empty(error.getMessage()), empty(error.getStackTrace()));
        executionRepository.flush();
        log(execution);
    }

    @Transactional
    public void failLocal(UUID executionId, Throwable error) {
        ProcedureExecution execution = executionRepository.findByExecutionId(executionId).orElse(null);
        if (execution == null || execution.getStatus() != ProcedureExecutionStatus.RUNNING)
            return;

        Instant finishedAt = Instant.now();
        execution.fail(null, execution.getStartedAt(), finishedAt, error.getClass().getName(),
                error.getMessage(), stackTrace(error));
        executionRepository.flush();
        log(execution);
    }

    private ProcedureExecution execution(UUID id) {
        return executionRepository.findByExecutionId(id)
                .orElseThrow(() -> new IllegalStateException("Procedure execution " + id + " was not found"));
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank())
            throw new IllegalArgumentException("Worker returned an empty procedure result");

        try {
            return jsonMapper.readTree(json);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Worker returned invalid procedure result JSON", exception);
        }
    }

    private void log(ProcedureExecution execution) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("executionId", execution.getExecutionId());
        data.put("procedureId", execution.getProcedure().getId());
        data.put("procedureName", execution.getProcedure().getName());
        data.put("status", execution.getStatus().name());
        data.put("depth", execution.getDepth());
        if (execution.getWorkStartedAt() != null)
            data.put("idleMillis", Duration.between(execution.getStartedAt(), execution.getWorkStartedAt()).toMillis());

        if (execution.getFinishedAt() != null && execution.getWorkStartedAt() != null)
            data.put("executionMillis", Duration.between(execution.getWorkStartedAt(), execution.getFinishedAt()).toMillis());

        if (execution.getStatus() == ProcedureExecutionStatus.ERROR) {
            data.put("errorType", execution.getErrorType());
            eventLogger.errorAfterCommit("PROCEDURE_EXECUTION_COMPLETED", data);
        } else {
            eventLogger.successAfterCommit("PROCEDURE_EXECUTION_COMPLETED", data);
        }
    }

    private static AlertExecutionWorker worker(WorkerEndpoint endpoint, String name, String instanceId) {
        if (endpoint == null)
            return null;

        return new AlertExecutionWorker(required(name, "Worker name"), endpoint.ipAddress(), endpoint.port(),
                UUID.fromString(required(instanceId, "Worker instance ID")));
    }

    private static Instant instant(com.google.protobuf.Timestamp value) {
        return Instant.ofEpochSecond(value.getSeconds(), value.getNanos());
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(label + " must not be blank");

        return value;
    }

    private static String empty(String value) { return value == null || value.isEmpty() ? null : value; }

    private static String stackTrace(Throwable error) {
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
