package app.alertify.hooks.model;

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
@Table(name = "hook_invocations", schema = "core")
public class HookInvocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invocation_id", nullable = false, updatable = false)
    private UUID invocationId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hook_id", nullable = false, updatable = false)
    private Hook hook;

    @Column(name = "hook_public_id", nullable = false, updatable = false)
    private UUID hookPublicId;

    @Column(name = "hook_name", nullable = false, updatable = false, columnDefinition = "text")
    private String hookName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private HookMode mode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private HookInvocationStatus status;

    @Column(name = "accepted_at", nullable = false, updatable = false)
    private Instant acceptedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @OneToMany(mappedBy = "invocation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    private List<HookInvocationTarget> targets = new ArrayList<>();

    protected HookInvocation() {
    }

    public HookInvocation(Hook hook) {
        this(hook, UUID.randomUUID());
    }

    public HookInvocation(Hook hook, UUID invocationId) {
        this.hook = Objects.requireNonNull(hook);
        this.invocationId = Objects.requireNonNull(invocationId);
        hookPublicId = hook.getPublicId();
        hookName = hook.getName();
        mode = hook.getMode();
        status = HookInvocationStatus.RUNNING;
        acceptedAt = Instant.now();
    }

    public Long getId() { return id; }
    public UUID getInvocationId() { return invocationId; }
    public Hook getHook() { return hook; }
    public UUID getHookPublicId() { return hookPublicId; }
    public String getHookName() { return hookName; }
    public HookMode getMode() { return mode; }
    public HookInvocationStatus getStatus() { return status; }
    public Instant getAcceptedAt() { return acceptedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public List<HookInvocationTarget> getTargets() { return Collections.unmodifiableList(targets); }
    public void addTarget(HookInvocationTarget target) { targets.add(target); }

    public void finish(HookInvocationStatus value) {
        if (value == HookInvocationStatus.RUNNING)
            throw new IllegalArgumentException("Terminal hook status is required");

        status = value;
        finishedAt = Instant.now();
    }
}
