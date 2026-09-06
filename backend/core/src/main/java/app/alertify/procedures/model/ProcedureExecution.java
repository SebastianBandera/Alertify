package app.alertify.procedures.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import app.alertify.alerts.model.AlertExecutionWorker;
import app.alertify.procedures.execution.ProcedureExecutionStatus;
import app.alertify.procedures.execution.ProcedureExecutionTrigger;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import tools.jackson.databind.JsonNode;

/**
 * Record of one procedure execution. It is intentionally not Envers-audited
 * because it is append-only execution history.
 *
 * <p>Unlike an alert execution, the row is written as RUNNING before the worker
 * is called and finalized afterwards, and it keeps its position in the
 * execution tree: the root execution it belongs to, the alert or procedure
 * execution that invoked it, and the nesting depth. When the template is marked
 * as producing a sensitive result the value is not stored and the row is
 * flagged as redacted.
 */
@Entity
@Table(name = "procedure_executions", schema = "core")
public class ProcedureExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "execution_id", nullable = false, updatable = false)
    private UUID executionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "procedure_id", nullable = false, updatable = false)
    private Procedure procedure;

    @Column(name = "procedure_version", nullable = false, updatable = false)
    private long procedureVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ProcedureExecutionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private ProcedureExecutionTrigger trigger;

    @Column(name = "root_execution_id", nullable = false, updatable = false)
    private UUID rootExecutionId;

    @Column(name = "parent_alert_execution_id", updatable = false)
    private UUID parentAlertExecutionId;

    @Column(name = "parent_procedure_execution_id", updatable = false)
    private UUID parentProcedureExecutionId;

    @Column(nullable = false, updatable = false)
    private int depth;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "work_started_at")
    private Instant workStartedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_json", columnDefinition = "jsonb")
    private JsonNode resultJson;

    @Column(name = "result_redacted", nullable = false)
    private boolean resultRedacted;

    @Column(name = "error_type", columnDefinition = "text")
    private String errorType;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "error_stack_trace", columnDefinition = "text")
    private String errorStackTrace;

    @Column(name = "worker_name")
    private String workerName;

    @Column(name = "worker_ip_address", length = 45)
    private String workerIpAddress;

    @Column(name = "worker_port")
    private Integer workerPort;

    @Column(name = "worker_instance_id")
    private UUID workerInstanceId;

    @Column(name = "triggered_by", columnDefinition = "text", updatable = false)
    private String triggeredBy;

    protected ProcedureExecution() {
    }

    public static ProcedureExecution running(UUID executionId, Procedure procedure, long procedureVersion, ProcedureExecutionTrigger trigger, UUID rootExecutionId, UUID parentAlertExecutionId, UUID parentProcedureExecutionId, int depth, Instant startedAt, String triggeredBy) {
        ProcedureExecution result = new ProcedureExecution();
        result.executionId = Objects.requireNonNull(executionId);
        result.procedure = Objects.requireNonNull(procedure);
        result.procedureVersion = procedureVersion;
        result.status = ProcedureExecutionStatus.RUNNING;
        result.trigger = Objects.requireNonNull(trigger);
        result.rootExecutionId = Objects.requireNonNull(rootExecutionId);
        result.parentAlertExecutionId = parentAlertExecutionId;
        result.parentProcedureExecutionId = parentProcedureExecutionId;
        result.depth = depth;
        result.startedAt = Objects.requireNonNull(startedAt);
        result.triggeredBy = triggeredBy;
        return result;
    }

    public void complete(AlertExecutionWorker worker, Instant workStartedAt, Instant finishedAt, JsonNode result, boolean redacted) {
        requireRunning();
        setWorker(worker);
        setTimes(workStartedAt, finishedAt);
        status = ProcedureExecutionStatus.COMPLETED;
        resultRedacted = redacted;
        resultJson = redacted ? null : Objects.requireNonNull(result, "result must not be null").deepCopy();
    }

    public void fail(AlertExecutionWorker worker, Instant workStartedAt, Instant finishedAt, String type, String message, String stackTrace) {
        requireRunning();
        setWorker(worker);
        setTimes(workStartedAt, finishedAt);
        status = ProcedureExecutionStatus.ERROR;
        errorType = Objects.requireNonNull(type, "type must not be null");
        errorMessage = message;
        errorStackTrace = stackTrace;
    }

    private void requireRunning() {
        if (status != ProcedureExecutionStatus.RUNNING)
            throw new IllegalStateException("Procedure execution is already complete");
    }

    private void setTimes(Instant workStartedAt, Instant finishedAt) {
        this.workStartedAt = Objects.requireNonNull(workStartedAt);
        this.finishedAt = Objects.requireNonNull(finishedAt);
        if (workStartedAt.isBefore(startedAt) || finishedAt.isBefore(workStartedAt))
            throw new IllegalArgumentException("Execution timestamps must be ordered");
    }

    private void setWorker(AlertExecutionWorker worker) {
        if (worker == null)
            return;

        workerName = worker.name();
        workerIpAddress = worker.ipAddress();
        workerPort = worker.port();
        workerInstanceId = worker.instanceId();
    }

    public Long getId() { return id; }
    public UUID getExecutionId() { return executionId; }
    public Procedure getProcedure() { return procedure; }
    public long getProcedureVersion() { return procedureVersion; }
    public ProcedureExecutionStatus getStatus() { return status; }
    public ProcedureExecutionTrigger getTrigger() { return trigger; }
    public UUID getRootExecutionId() { return rootExecutionId; }
    public UUID getParentAlertExecutionId() { return parentAlertExecutionId; }
    public UUID getParentProcedureExecutionId() { return parentProcedureExecutionId; }
    public int getDepth() { return depth; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getWorkStartedAt() { return workStartedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public JsonNode getResultJson() { return resultJson == null ? null : resultJson.deepCopy(); }
    public boolean isResultRedacted() { return resultRedacted; }
    public String getErrorType() { return errorType; }
    public String getErrorMessage() { return errorMessage; }
    public String getErrorStackTrace() { return errorStackTrace; }
    public String getWorkerName() { return workerName; }
    public String getWorkerIpAddress() { return workerIpAddress; }
    public Integer getWorkerPort() { return workerPort; }
    public UUID getWorkerInstanceId() { return workerInstanceId; }
    public String getTriggeredBy() { return triggeredBy; }
}
