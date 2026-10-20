package app.alertify.ai.memory;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

@Entity
@Table(name = "ai_memories", schema = "core", uniqueConstraints = @UniqueConstraint(name = "uq_ai_memories_user_key", columnNames = { "user_subject", "memory_key" }))
public class AiMemory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_subject", nullable = false, updatable = false, columnDefinition = "text")
    private String userSubject;
    @Column(name = "memory_key", nullable = false, updatable = false, length = 96)
    private String memoryKey;
    @Column(nullable = false, columnDefinition = "text")
    private String content;
    @Version
    @Column(nullable = false)
    private long version;
    @Column(name = "source_conversation_id", updatable = false)
    private Long sourceConversationId;
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AiMemory() {
    }

    public AiMemory(String userSubject, String memoryKey, String content, Long sourceConversationId) {
        this.userSubject = userSubject;
        this.memoryKey = memoryKey;
        this.content = content;
        this.sourceConversationId = sourceConversationId;
    }

    public Long getId() { return id; }
    public String getUserSubject() { return userSubject; }
    public String getMemoryKey() { return memoryKey; }
    public String getContent() { return content; }
    public long getVersion() { return version; }
    public Long getSourceConversationId() { return sourceConversationId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void replaceContent(String content) {
        this.content = content;
    }
}
