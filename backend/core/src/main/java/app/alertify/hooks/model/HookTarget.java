package app.alertify.hooks.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.envers.AuditTable;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;
import org.hibernate.type.SqlTypes;

import app.alertify.alerts.model.Alert;
import app.alertify.procedures.model.Procedure;
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
import jakarta.persistence.Version;

@Entity
@Audited
@AuditTable(value = "hook_targets_aud", schema = "audit")
@Table(name = "hook_targets", schema = "core")
public class HookTarget {

    public static final long DEFAULT_BUSY_WAIT_MILLIS = 30L * 60L * 1000L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @NotAudited
    @Column(nullable = false)
    private long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hook_id", nullable = false)
    private Hook hook;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 16)
    private HookTargetType targetType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "alert_id")
    private Alert alert;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "procedure_id")
    private Procedure procedure;

    @Column(nullable = false)
    private int position;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "continue_on", nullable = false, columnDefinition = "jsonb")
    private List<String> continueOn = new ArrayList<>();

    @Column(name = "busy_wait_timeout_millis")
    private Long busyWaitTimeoutMillis;

    @CreationTimestamp
    @NotAudited
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @NotAudited
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected HookTarget() {
    }

    private HookTarget(Hook hook, HookTargetType targetType, Alert alert, Procedure procedure, int position, List<String> continueOn, Long busyWaitTimeoutMillis) {
        this.hook = Objects.requireNonNull(hook);
        this.targetType = Objects.requireNonNull(targetType);
        this.alert = alert;
        this.procedure = procedure;
        this.position = position;
        this.continueOn.addAll(continueOn);
        this.busyWaitTimeoutMillis = busyWaitTimeoutMillis;
    }

    public static HookTarget alert(Hook hook, Alert alert, int position, List<String> continueOn, long busyWaitTimeoutMillis) {
        return new HookTarget(hook, HookTargetType.ALERT, Objects.requireNonNull(alert), null, position, continueOn, busyWaitTimeoutMillis);
    }

    public static HookTarget procedure(Hook hook, Procedure procedure, int position, List<String> continueOn) {
        return new HookTarget(hook, HookTargetType.PROCEDURE, null, Objects.requireNonNull(procedure), position, continueOn, null);
    }

    public Long getId() { return id; }
    public HookTargetType getTargetType() { return targetType; }
    public Alert getAlert() { return alert; }
    public Procedure getProcedure() { return procedure; }
    public int getPosition() { return position; }
    public List<String> getContinueOn() { return Collections.unmodifiableList(continueOn); }
    public Long getBusyWaitTimeoutMillis() { return busyWaitTimeoutMillis; }

    public void moveTemporarily(int value) { position = value; }

    public void reconfigure(int value, List<String> outcomes, Long timeoutMillis) {
        position = value;
        continueOn.clear();
        continueOn.addAll(outcomes);
        busyWaitTimeoutMillis = timeoutMillis;
    }
}
