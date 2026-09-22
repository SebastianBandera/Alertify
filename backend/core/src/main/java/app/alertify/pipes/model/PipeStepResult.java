package app.alertify.pipes.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

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

@Entity
@Table(name = "pipe_step_results", schema = "core")
public class PipeStepResult {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pipe_execution_id", nullable = false, updatable = false)
    private PipeExecution execution;

    @Column(name = "step_key", nullable = false, updatable = false, columnDefinition = "text")
    private String stepKey;

    @Column(nullable = false, updatable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @Column(name = "step_type", nullable = false, updatable = false, length = 16)
    private PipeStepType stepType;

    @Column(name = "resource_id", nullable = false, updatable = false)
    private long resourceId;

    @Column(name = "resource_name", nullable = false, updatable = false, columnDefinition = "text")
    private String resourceName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PipeStepStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private PipeOutcome outcome;

    @Column(name = "resource_execution_id")
    private UUID resourceExecutionId;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    protected PipeStepResult() {
    }

    public PipeStepResult(PipeExecution execution, PipeStep step) {
        this.execution = Objects.requireNonNull(execution);
        stepKey = step.getStepKey();
        position = step.getPosition();
        stepType = step.getStepType();
        resourceId = stepType == PipeStepType.ALERT ? step.getAlert().getId() : step.getProcedure().getId();
        resourceName = stepType == PipeStepType.ALERT ? step.getAlert().getName() : step.getProcedure().getName();
        status = PipeStepStatus.PENDING;
    }

    public void start() {
        status = PipeStepStatus.RUNNING;
        startedAt = Instant.now();
    }

    public void complete(PipeStepStatus status, PipeOutcome outcome, UUID resourceExecutionId, String errorCode) {
        this.status = Objects.requireNonNull(status);
        this.outcome = outcome;
        this.resourceExecutionId = resourceExecutionId;
        this.errorCode = errorCode;
        if (startedAt == null)
            startedAt = Instant.now();

        finishedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getStepKey() { return stepKey; }
    public int getPosition() { return position; }
    public PipeStepType getStepType() { return stepType; }
    public long getResourceId() { return resourceId; }
    public String getResourceName() { return resourceName; }
    public PipeStepStatus getStatus() { return status; }
    public PipeOutcome getOutcome() { return outcome; }
    public UUID getResourceExecutionId() { return resourceExecutionId; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public String getErrorCode() { return errorCode; }
}
