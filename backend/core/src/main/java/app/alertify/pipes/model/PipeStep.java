package app.alertify.pipes.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.envers.AuditTable;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;
import org.hibernate.type.SqlTypes;

import app.alertify.alerts.model.Alert;
import app.alertify.procedures.model.Procedure;
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
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Audited
@AuditTable(value = "pipe_steps_aud", schema = "audit")
@Table(name = "pipe_steps", schema = "core")
public class PipeStep {
    public static final long DEFAULT_TIMEOUT_MILLIS = 30L * 60L * 1000L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @NotAudited
    @Column(nullable = false)
    private long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pipe_id", nullable = false)
    private Pipe pipe;

    @Column(name = "step_key", nullable = false, columnDefinition = "text")
    private String stepKey;

    @Column(nullable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @Column(name = "step_type", nullable = false, length = 16)
    private PipeStepType stepType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "alert_id")
    private Alert alert;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "procedure_id")
    private Procedure procedure;

    @Column(name = "timeout_millis", nullable = false)
    private long timeoutMillis;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "continue_on", nullable = false, columnDefinition = "jsonb")
    private List<String> continueOn = new ArrayList<>();

    @OneToMany(mappedBy = "targetStep", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<PipeStepBinding> bindings = new LinkedHashSet<>();

    @CreationTimestamp
    @NotAudited
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @NotAudited
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PipeStep() {
    }

    private PipeStep(Pipe pipe, String stepKey, int position, PipeStepType stepType, Alert alert, Procedure procedure, long timeoutMillis, List<String> continueOn) {
        this.pipe = Objects.requireNonNull(pipe);
        this.stepKey = Objects.requireNonNull(stepKey);
        this.position = position;
        this.stepType = Objects.requireNonNull(stepType);
        this.alert = alert;
        this.procedure = procedure;
        this.timeoutMillis = timeoutMillis;
        this.continueOn.addAll(Objects.requireNonNull(continueOn));
    }

    public static PipeStep alert(Pipe pipe, String key, int position, Alert alert, long timeoutMillis, List<String> continueOn) {
        return new PipeStep(pipe, key, position, PipeStepType.ALERT, Objects.requireNonNull(alert), null, timeoutMillis, continueOn);
    }

    public static PipeStep procedure(Pipe pipe, String key, int position, Procedure procedure, long timeoutMillis, List<String> continueOn) {
        return new PipeStep(pipe, key, position, PipeStepType.PROCEDURE, null, Objects.requireNonNull(procedure), timeoutMillis, continueOn);
    }

    public void addBinding(PipeStepBinding value) { bindings.add(Objects.requireNonNull(value)); }
    public void clearBindings() { bindings.clear(); }
    public void moveTemporarily(int value) { position = value; }
    public Long getId() { return id; }
    public Pipe getPipe() { return pipe; }
    public String getStepKey() { return stepKey; }
    public int getPosition() { return position; }
    public PipeStepType getStepType() { return stepType; }
    public Alert getAlert() { return alert; }
    public Procedure getProcedure() { return procedure; }
    public long getTimeoutMillis() { return timeoutMillis; }
    public List<String> getContinueOn() { return Collections.unmodifiableList(continueOn); }
    public List<PipeStepBinding> getBindings() { return List.copyOf(bindings); }
}
