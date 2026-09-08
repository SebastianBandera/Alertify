package app.alertify.hooks.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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
@Table(name = "hook_invocation_targets", schema = "core")
public class HookInvocationTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hook_invocation_id", nullable = false, updatable = false)
    private HookInvocation invocation;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, updatable = false, length = 16)
    private HookTargetType targetType;

    @Column(name = "resource_id", nullable = false, updatable = false)
    private long resourceId;

    @Column(name = "resource_name", nullable = false, updatable = false, columnDefinition = "text")
    private String resourceName;

    @Column(nullable = false, updatable = false)
    private int position;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "continue_on", nullable = false, updatable = false, columnDefinition = "jsonb")
    private List<String> continueOn = new ArrayList<>();

    @Column(name = "busy_wait_timeout_millis", updatable = false)
    private Long busyWaitTimeoutMillis;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private HookTargetStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private HookOutcome outcome;

    @Column(name = "execution_id")
    private UUID executionId;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    protected HookInvocationTarget() {
    }

    public HookInvocationTarget(HookInvocation invocation, HookTarget target) {
        this.invocation = Objects.requireNonNull(invocation);
        targetType = target.getTargetType();
        resourceId = targetType == HookTargetType.ALERT ? target.getAlert().getId() : target.getProcedure().getId();
        resourceName = targetType == HookTargetType.ALERT ? target.getAlert().getName() : target.getProcedure().getName();
        position = target.getPosition();
        continueOn.addAll(target.getContinueOn());
        busyWaitTimeoutMillis = target.getBusyWaitTimeoutMillis();
        status = HookTargetStatus.PENDING;
    }

    public Long getId() { return id; }
    public HookTargetType getTargetType() { return targetType; }
    public long getResourceId() { return resourceId; }
    public String getResourceName() { return resourceName; }
    public int getPosition() { return position; }
    public List<String> getContinueOn() { return Collections.unmodifiableList(continueOn); }
    public Long getBusyWaitTimeoutMillis() { return busyWaitTimeoutMillis; }
    public HookTargetStatus getStatus() { return status; }
    public HookOutcome getOutcome() { return outcome; }
    public UUID getExecutionId() { return executionId; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public String getErrorCode() { return errorCode; }

    public void transition(HookTargetStatus value) {
        status = Objects.requireNonNull(value);
        if (startedAt == null && (value == HookTargetStatus.WAITING_ALERT || value == HookTargetStatus.RUNNING))
            startedAt = Instant.now();
    }

    public void complete(HookTargetStatus value, HookOutcome result, UUID executionId, String errorCode) {
        status = Objects.requireNonNull(value);
        outcome = result;
        this.executionId = executionId;
        this.errorCode = errorCode;
        if (startedAt == null)
            startedAt = Instant.now();

        finishedAt = Instant.now();
    }
}
