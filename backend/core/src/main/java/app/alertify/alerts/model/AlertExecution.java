package app.alertify.alerts.model;

import java.time.Instant;
import java.util.Objects;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import app.alertify.alerts.execution.AlertExecutionStatus;
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

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "alert_id", nullable = false, updatable = false)
    private Alert alert;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private AlertExecutionStatus status;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

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

    protected AlertExecution() {
    }

    private AlertExecution(Alert alert, AlertExecutionStatus status, Instant startedAt, Instant finishedAt, JsonNode statusMessage, String errorType, String errorMessage, String errorStackTrace) {
        this.alert = Objects.requireNonNull(alert, "alert must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
        this.finishedAt = Objects.requireNonNull(finishedAt, "finishedAt must not be null");
        if (finishedAt.isBefore(startedAt))
            throw new IllegalArgumentException("finishedAt must not be before startedAt");
        this.statusMessage = statusMessage == null ? null : statusMessage.deepCopy();
        this.errorType = errorType;
        this.errorMessage = errorMessage;
        this.errorStackTrace = errorStackTrace;
    }

    public static AlertExecution result(Alert alert, AlertExecutionStatus status, Instant startedAt, Instant finishedAt, JsonNode statusMessage) {
        if (status == AlertExecutionStatus.ERROR)
            throw new IllegalArgumentException("ERROR is reserved for exception executions");
        return new AlertExecution(alert, status, startedAt, finishedAt, statusMessage, null, null, null);
    }

    public static AlertExecution error(Alert alert, Instant startedAt, Instant finishedAt, String errorType, String errorMessage, String errorStackTrace) {
        return new AlertExecution(
                alert, AlertExecutionStatus.ERROR, startedAt, finishedAt, null,
                Objects.requireNonNull(errorType, "errorType must not be null"), errorMessage, errorStackTrace
        );
    }

    public Long getId() {
        return id;
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
}
