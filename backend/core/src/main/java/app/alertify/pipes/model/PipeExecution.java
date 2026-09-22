package app.alertify.pipes.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

@Entity
@Table(name = "pipe_executions", schema = "core")
public class PipeExecution {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "execution_id", nullable = false, updatable = false)
    private UUID executionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pipe_id", nullable = false, updatable = false)
    private Pipe pipe;

    @Column(name = "pipe_version", nullable = false, updatable = false)
    private long pipeVersion;

    @Column(name = "pipe_name", nullable = false, updatable = false, columnDefinition = "text")
    private String pipeName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PipeExecutionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private PipeOutcome outcome;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private PipeExecutionTrigger trigger;

    @Column(name = "root_execution_id", nullable = false, updatable = false)
    private UUID rootExecutionId;

    @Column(name = "parent_procedure_execution_id", updatable = false)
    private UUID parentProcedureExecutionId;

    @Column(nullable = false, updatable = false)
    private int depth;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "triggered_by", updatable = false, columnDefinition = "text")
    private String triggeredBy;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @OneToMany(mappedBy = "execution", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    private List<PipeStepResult> steps = new ArrayList<>();

    protected PipeExecution() {
    }

    public PipeExecution(UUID executionId, Pipe pipe, PipeExecutionTrigger trigger, UUID rootExecutionId, UUID parentProcedureExecutionId, int depth, String triggeredBy) {
        this.executionId = Objects.requireNonNull(executionId);
        this.pipe = Objects.requireNonNull(pipe);
        pipeVersion = pipe.getVersion();
        pipeName = pipe.getName();
        status = PipeExecutionStatus.RUNNING;
        this.trigger = Objects.requireNonNull(trigger);
        this.rootExecutionId = Objects.requireNonNull(rootExecutionId);
        this.parentProcedureExecutionId = parentProcedureExecutionId;
        this.depth = depth;
        this.triggeredBy = triggeredBy;
        startedAt = Instant.now();
    }

    public void addStep(PipeStepResult value) { steps.add(Objects.requireNonNull(value)); }

    public void finish(PipeExecutionStatus status, PipeOutcome outcome, String errorCode) {
        if (status == PipeExecutionStatus.RUNNING)
            throw new IllegalArgumentException("Terminal pipe status is required");

        this.status = Objects.requireNonNull(status);
        this.outcome = Objects.requireNonNull(outcome);
        this.errorCode = errorCode;
        finishedAt = Instant.now();
    }

    public Long getId() { return id; }
    public UUID getExecutionId() { return executionId; }
    public Pipe getPipe() { return pipe; }
    public long getPipeVersion() { return pipeVersion; }
    public String getPipeName() { return pipeName; }
    public PipeExecutionStatus getStatus() { return status; }
    public PipeOutcome getOutcome() { return outcome; }
    public PipeExecutionTrigger getTrigger() { return trigger; }
    public UUID getRootExecutionId() { return rootExecutionId; }
    public UUID getParentProcedureExecutionId() { return parentProcedureExecutionId; }
    public int getDepth() { return depth; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public String getTriggeredBy() { return triggeredBy; }
    public String getErrorCode() { return errorCode; }
    public List<PipeStepResult> getSteps() { return Collections.unmodifiableList(steps); }
}
