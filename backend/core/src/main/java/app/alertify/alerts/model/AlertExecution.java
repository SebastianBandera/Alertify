package app.alertify.alerts.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import app.alertify.alerts.execution.AlertExecutionStatus;
import app.alertify.alerts.execution.AlertExecutionTrigger;
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
 * Immutable record of a completed alert execution. It is intentionally not
 * Envers-audited because it is append-only execution history.
 */
@Entity
@Table(name = "alert_executions", schema = "core")
public class AlertExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "execution_id", nullable = false, updatable = false)
    private UUID executionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "alert_id", nullable = false, updatable = false)
    private Alert alert;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private AlertExecutionStatus status;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "work_started_at", nullable = false, updatable = false)
    private Instant workStartedAt;

    @Column(name = "finished_at", nullable = false, updatable = false)
    private Instant finishedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "status_message", columnDefinition = "jsonb", updatable = false)
    private JsonNode statusMessage;

    @Column(name = "error_type", columnDefinition = "text", updatable = false)
    private String errorType;

    @Column(name = "error_message", columnDefinition = "text", updatable = false)
    private String errorMessage;

    @Column(name = "error_stack_trace", columnDefinition = "text", updatable = false)
    private String errorStackTrace;

    @Column(name = "worker_name", updatable = false)
    private String workerName;

    @Column(name = "worker_ip_address", length = 45, updatable = false)
    private String workerIpAddress;

    @Column(name = "worker_port", updatable = false)
    private Integer workerPort;

    @Column(name = "worker_instance_id", updatable = false)
    private UUID workerInstanceId;

    @Enumerated(EnumType.STRING)
    @Column(length = 16, updatable = false)
    private AlertExecutionTrigger trigger;

    @Column(name = "triggered_by", columnDefinition = "text", updatable = false)
    private String triggeredBy;

    @Column(name = "parent_pipe_execution_id", updatable = false)
    private UUID parentPipeExecutionId;

    @Column(name = "parent_step_key", columnDefinition = "text", updatable = false)
    private String parentStepKey;

    @Column(name = "parent_hook_invocation_id", updatable = false)
    private UUID parentHookInvocationId;

    @Column(name = "parent_hook_name", columnDefinition = "text", updatable = false)
    private String parentHookName;

    @Column(nullable = false)
    private boolean closed;

    @Column(name = "closure_at")
    private Instant closureAt;

    @Column(name = "closure_by", columnDefinition = "text")
    private String closureBy;

    @Column(name = "closure_note", columnDefinition = "text")
    private String closureNote;

    public void changeClosure(boolean closed, String actor, String note, Instant at) {
        if (status != AlertExecutionStatus.WARN && status != AlertExecutionStatus.ERROR)
            throw new IllegalArgumentException("Only WARN and ERROR executions may be closed or reopened");

        this.closed = closed;
        closureAt = Objects.requireNonNull(at);
        closureBy = Objects.requireNonNull(actor);
        closureNote = note;
    }

    public boolean isClosed() { return closed; }
    public Instant getClosureAt() { return closureAt; }
    public String getClosureBy() { return closureBy; }
    public String getClosureNote() { return closureNote; }

    public void recordPipeParent(UUID executionId, String stepKey) {
        if ((executionId == null) != (stepKey == null) || executionId != null && (trigger != AlertExecutionTrigger.PIPE || stepKey.isBlank() || parentHookInvocationId != null))
            throw new IllegalArgumentException("Pipe parent requires PIPE origin and a step key");

        parentPipeExecutionId = executionId;
        parentStepKey = stepKey;
    }

    public UUID getParentPipeExecutionId() { return parentPipeExecutionId; }
    public String getParentStepKey() { return parentStepKey; }

    public void recordHookParent(UUID invocationId, String hookName) {
        if ((invocationId == null) != (hookName == null) || invocationId != null && (trigger != AlertExecutionTrigger.HOOK || hookName.isBlank() || parentPipeExecutionId != null))
            throw new IllegalArgumentException("Hook parent requires HOOK origin and a hook name");

        parentHookInvocationId = invocationId;
        parentHookName = hookName;
    }

    public UUID getParentHookInvocationId() { return parentHookInvocationId; }
    public String getParentHookName() { return parentHookName; }

    protected AlertExecution() {
    }

    private AlertExecution(UUID executionId, Alert alert, AlertExecutionWorker worker, AlertExecutionStatus status, Instant startedAt, Instant workStartedAt, Instant finishedAt, JsonNode statusMessage, String errorType, String errorMessage, String errorStackTrace, AlertExecutionTrigger trigger, String triggeredBy) {
        this.executionId = Objects.requireNonNull(executionId, "executionId must not be null");
        this.alert = Objects.requireNonNull(alert, "alert must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
        this.workStartedAt = Objects.requireNonNull(workStartedAt, "workStartedAt must not be null");
        this.finishedAt = Objects.requireNonNull(finishedAt, "finishedAt must not be null");
        if (workStartedAt.isBefore(startedAt) || finishedAt.isBefore(workStartedAt))
            throw new IllegalArgumentException("Execution timestamps must be ordered");

        this.statusMessage = statusMessage == null ? null : statusMessage.deepCopy();
        this.errorType = errorType;
        this.errorMessage = errorMessage;
        this.errorStackTrace = errorStackTrace;
        this.trigger = trigger;
        this.triggeredBy = triggeredBy;
        if (worker != null) {
            this.workerName = worker.name();
            this.workerIpAddress = worker.ipAddress();
            this.workerPort = worker.port();
            this.workerInstanceId = worker.instanceId();
        }
    }

    public static AlertExecution result(UUID executionId, Alert alert, AlertExecutionWorker worker, AlertExecutionStatus status, Instant startedAt, Instant workStartedAt, Instant finishedAt, JsonNode statusMessage) {
        return result(executionId, alert, worker, status, startedAt, workStartedAt, finishedAt, statusMessage, null, null);
    }

    public static AlertExecution result(UUID executionId, Alert alert, AlertExecutionWorker worker, AlertExecutionStatus status, Instant startedAt, Instant workStartedAt, Instant finishedAt, JsonNode statusMessage, AlertExecutionTrigger trigger, String triggeredBy) {
        if (status == AlertExecutionStatus.ERROR)
            throw new IllegalArgumentException("ERROR is reserved for exception executions");

        return new AlertExecution(executionId, alert, worker, status, startedAt, workStartedAt, finishedAt, statusMessage, null, null, null, trigger, triggeredBy);
    }

    public static AlertExecution error(UUID executionId, Alert alert, AlertExecutionWorker worker, Instant startedAt, Instant workStartedAt, Instant finishedAt, String errorType, String errorMessage, String errorStackTrace) {
        return error(executionId, alert, worker, startedAt, workStartedAt, finishedAt, errorType, errorMessage, errorStackTrace, null, null);
    }

    public static AlertExecution error(UUID executionId, Alert alert, AlertExecutionWorker worker, Instant startedAt, Instant workStartedAt, Instant finishedAt, String errorType, String errorMessage, String errorStackTrace, AlertExecutionTrigger trigger, String triggeredBy) {
        return new AlertExecution(
                executionId, alert, worker, AlertExecutionStatus.ERROR, startedAt, workStartedAt, finishedAt, null,
                Objects.requireNonNull(errorType, "errorType must not be null"), errorMessage, errorStackTrace, trigger, triggeredBy
        );
    }

    public static AlertExecution observed(UUID executionId, Alert alert, AlertExecutionStatus status, Instant startedAt, Instant finishedAt, JsonNode summary, AlertExecutionTrigger trigger, String triggeredBy) {
        return new AlertExecution(executionId, alert, null, status, startedAt, startedAt, finishedAt, summary,
                status == AlertExecutionStatus.ERROR ? "OBSERVED_RESOURCE_ERROR" : null,
                status == AlertExecutionStatus.ERROR ? "The observed resource's latest terminal execution failed" : null,
                null, trigger, triggeredBy);
    }

    public Long getId() {
        return id;
    }

    public UUID getExecutionId() {
        return executionId;
    }

    public Alert getAlert() {
        return alert;
    }

    public AlertExecutionStatus getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Instant getWorkStartedAt() {
        return workStartedAt;
    }

    public JsonNode getStatusMessage() {
        return statusMessage == null ? null : statusMessage.deepCopy();
    }

    public String getErrorType() {
        return errorType;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getErrorStackTrace() {
        return errorStackTrace;
    }

    public String getWorkerName() {
        return workerName;
    }

    public String getWorkerIpAddress() {
        return workerIpAddress;
    }

    public Integer getWorkerPort() {
        return workerPort;
    }

    public UUID getWorkerInstanceId() {
        return workerInstanceId;
    }

    public AlertExecutionTrigger getTrigger() { return trigger; }
    public String getTriggeredBy() { return triggeredBy; }
}
