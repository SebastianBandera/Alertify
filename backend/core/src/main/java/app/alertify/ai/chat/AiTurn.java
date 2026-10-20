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

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "ai_turns", schema = "core")
public class AiTurn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false, updatable = false)
    private AiConversation conversation;
    @Column(nullable = false, length = 24)
    private String status;
    @Column(nullable = false, length = 128)
    private String model;
    @Column(name = "reasoning_effort", nullable = false, length = 16)
    private String reasoningEffort;
    @Column(name = "reasoning_upgraded", nullable = false)
    private boolean reasoningUpgraded;
    @Column(name = "rejection_count", nullable = false)
    private int rejectionCount;
    @Column(name = "error_code", length = 64)
    private String errorCode;
    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "finished_at")
    private Instant finishedAt;
    @Column(name = "model_request_count", nullable = false)
    private int modelRequestCount;
    @Column(name = "input_tokens", nullable = false)
    private long inputTokens;
    @Column(name = "cached_input_tokens", nullable = false)
    private long cachedInputTokens;
    @Column(name = "cache_write_input_tokens", nullable = false)
    private long cacheWriteInputTokens;
    @Column(name = "output_tokens", nullable = false)
    private long outputTokens;
    @Column(name = "reasoning_output_tokens", nullable = false)
    private long reasoningOutputTokens;
    @Column(name = "total_tokens", nullable = false)
    private long totalTokens;
    @Column(name = "last_request_total_tokens", nullable = false)
    private long lastRequestTotalTokens;
    @Column(name = "model_context_window")
    private Long modelContextWindow;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "quota_snapshot", columnDefinition = "jsonb")
    private AiQuotaSnapshotValue quotaSnapshot;
    @Column(name = "usage_observed_at")
    private Instant usageObservedAt;

    protected AiTurn() {
    }

    public AiTurn(AiConversation conversation, String model, String reasoningEffort) {
        this.conversation = conversation;
        this.model = model;
        this.reasoningEffort = reasoningEffort;
        status = "RUNNING";
        createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public AiConversation getConversation() { return conversation; }
    public String getStatus() { return status; }
    public String getModel() { return model; }
    public String getReasoningEffort() { return reasoningEffort; }
    public boolean isReasoningUpgraded() { return reasoningUpgraded; }
    public int getRejectionCount() { return rejectionCount; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public int getModelRequestCount() { return modelRequestCount; }
    public long getInputTokens() { return inputTokens; }
    public long getCachedInputTokens() { return cachedInputTokens; }
    public long getCacheWriteInputTokens() { return cacheWriteInputTokens; }
    public long getOutputTokens() { return outputTokens; }
    public long getReasoningOutputTokens() { return reasoningOutputTokens; }
    public long getTotalTokens() { return totalTokens; }
    public long getLastRequestTotalTokens() { return lastRequestTotalTokens; }
    public Long getModelContextWindow() { return modelContextWindow; }
    public AiQuotaSnapshotValue getQuotaSnapshot() { return quotaSnapshot; }
    public Instant getUsageObservedAt() { return usageObservedAt; }

    public void waitingApproval() { status = "WAITING_APPROVAL"; }
    public void running() { status = "RUNNING"; }
    public void upgraded(String effort) { reasoningEffort = effort; reasoningUpgraded = true; }
    public void rejected() { rejectionCount++; }
    public void recordUsage(AiTurnUsageUpdate usage) {
        AiTokenUsageValue request = usage.request();
        modelRequestCount = Math.addExact(modelRequestCount, 1);
        inputTokens = Math.addExact(inputTokens, request.inputTokens());
        cachedInputTokens = Math.addExact(cachedInputTokens, request.cachedInputTokens());
        cacheWriteInputTokens = Math.addExact(cacheWriteInputTokens, request.cacheWriteInputTokens());
        outputTokens = Math.addExact(outputTokens, request.outputTokens());
        reasoningOutputTokens = Math.addExact(reasoningOutputTokens, request.reasoningOutputTokens());
        totalTokens = Math.addExact(totalTokens, request.totalTokens());
        lastRequestTotalTokens = request.totalTokens();
        if (usage.modelContextWindow() > 0)
            modelContextWindow = usage.modelContextWindow();

        quotaSnapshot = usage.quota();
        usageObservedAt = Instant.ofEpochSecond(usage.quota().observedAtEpochSeconds());
    }
    public void complete() { status = "COMPLETED"; finishedAt = Instant.now(); }
    public void cancel() { status = "CANCELLED"; finishedAt = Instant.now(); }
    public void fail(String code, String message) { status = "FAILED"; errorCode = code; errorMessage = message; finishedAt = Instant.now(); }
}
