package app.alertify.alerts.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** An immutable closure event independent of the lifetime of its alert and execution. */
@Entity
@Immutable
@Table(name = "alert_execution_closure_audit", schema = "audit")
public class AlertExecutionClosureAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "execution_id", nullable = false, updatable = false)
    private UUID executionId;

    @Column(name = "alert_id", nullable = false, updatable = false)
    private long alertId;

    @Column(nullable = false, updatable = false)
    private boolean closed;

    @Column(name = "actor_subject", nullable = false, columnDefinition = "text", updatable = false)
    private String actorSubject;

    @Column(name = "actor_name", nullable = false, columnDefinition = "text", updatable = false)
    private String actorName;

    @Column(columnDefinition = "text", updatable = false)
    private String note;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    protected AlertExecutionClosureAudit() {
    }

    public AlertExecutionClosureAudit(UUID executionId, long alertId, boolean closed, String actorSubject, String actorName, String note, Instant changedAt) {
        this.executionId = Objects.requireNonNull(executionId);
        this.alertId = alertId;
        this.closed = closed;
        this.actorSubject = Objects.requireNonNull(actorSubject);
        this.actorName = Objects.requireNonNull(actorName);
        this.note = note;
        this.changedAt = Objects.requireNonNull(changedAt);
    }

    public Long getId() { return id; }
    public UUID getExecutionId() { return executionId; }
    public long getAlertId() { return alertId; }
    public boolean isClosed() { return closed; }
    public String getActorSubject() { return actorSubject; }
    public String getActorName() { return actorName; }
    public String getNote() { return note; }
    public Instant getChangedAt() { return changedAt; }
}
