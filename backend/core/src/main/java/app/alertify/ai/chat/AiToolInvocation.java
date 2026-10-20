package app.alertify.ai.chat;

import java.time.Instant;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "ai_tool_invocations", schema = "core")
public class AiToolInvocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "turn_id", nullable = false, updatable = false)
    private AiTurn turn;
    @Column(name = "call_id", nullable = false, updatable = false, columnDefinition = "text")
    private String callId;
    @Column(nullable = false, updatable = false)
    private long generation;
    @Column(name = "tool_name", nullable = false, updatable = false, columnDefinition = "text")
    private String toolName;
    @Column(nullable = false, updatable = false, length = 16)
    private String kind;
    @Column(nullable = false, updatable = false)
    private boolean declared;
    @Column(nullable = false, updatable = false)
    private boolean allowed;
    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String arguments;
    @Column(columnDefinition = "text")
    private String result;
    @Column(nullable = false, length = 24)
    private String status;
    @Column(name = "read_only", nullable = false, updatable = false)
    private boolean readOnly;
    @Column(name = "destructive_hint", nullable = false, updatable = false)
    private boolean destructiveHint;
    @Column(name = "idempotent_hint", nullable = false, updatable = false)
    private boolean idempotentHint;
    @Column(name = "open_world_hint", nullable = false, updatable = false)
    private boolean openWorldHint;
    @Column(name = "refresh_targets", nullable = false, updatable = false, columnDefinition = "text")
    private String refreshTargets;
    @Column(name = "review_reason", columnDefinition = "text")
    private String reviewReason;
    @Column(name = "diagnostic_code", length = 64)
    private String diagnosticCode;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "finished_at")
    private Instant finishedAt;

    protected AiToolInvocation() {
    }

    public AiToolInvocation(AiTurn turn, long generation, String callId, String toolName, String arguments, String kind,
            boolean declared, boolean allowed, boolean readOnly, boolean destructiveHint, boolean idempotentHint,
            boolean openWorldHint, String refreshTargets, boolean waitingApproval) {
        this.turn = turn;
        this.generation = generation;
        this.callId = callId;
        this.toolName = toolName;
        this.kind = kind;
        this.declared = declared;
        this.allowed = allowed;
        this.arguments = arguments;
        this.readOnly = readOnly;
        this.destructiveHint = destructiveHint;
        this.idempotentHint = idempotentHint;
        this.openWorldHint = openWorldHint;
        this.refreshTargets = refreshTargets;
        status = waitingApproval ? "PENDING_APPROVAL" : "RUNNING";
        createdAt = Instant.now();
    }

    public Long getId() { return id; }
    AiTurn getTurn() { return turn; }
    public String getCallId() { return callId; }
    public long getGeneration() { return generation; }
    public String getToolName() { return toolName; }
    public String getKind() { return kind; }
    public boolean isDeclared() { return declared; }
    public boolean isAllowed() { return allowed; }
    public String getArguments() { return arguments; }
    public String getResult() { return result; }
    public String getStatus() { return status; }
    public boolean isReadOnly() { return readOnly; }
    public boolean isDestructiveHint() { return destructiveHint; }
    public boolean isIdempotentHint() { return idempotentHint; }
    public boolean isOpenWorldHint() { return openWorldHint; }
    public String getRefreshTargets() { return refreshTargets; }
    public String getReviewReason() { return reviewReason; }
    public String getDiagnosticCode() { return diagnosticCode; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getFinishedAt() { return finishedAt; }

    public void running(String reason) { status = "RUNNING"; reviewReason = reason; diagnosticCode = null; }
    public void succeed(String output) { status = "SUCCEEDED"; result = output; diagnosticCode = null; finishedAt = Instant.now(); }
    public void fail(String output, String code) { status = "FAILED"; result = output; diagnosticCode = code; finishedAt = Instant.now(); }
    public void reject(String reason, String code) { status = "REJECTED"; reviewReason = reason; diagnosticCode = code; finishedAt = Instant.now(); }
    public void deny(String output, String code) { status = "REJECTED"; result = output; diagnosticCode = code; finishedAt = Instant.now(); }
    public void cancel(String code) { status = "CANCELLED"; diagnosticCode = code; finishedAt = Instant.now(); }
    public void interrupt(String code) { status = "INTERRUPTED"; diagnosticCode = code; finishedAt = Instant.now(); }
}
