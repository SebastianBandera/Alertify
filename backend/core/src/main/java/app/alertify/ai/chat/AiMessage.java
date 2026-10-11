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
@Table(name = "ai_messages", schema = "core")
public class AiMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false, updatable = false)
    private AiConversation conversation;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "turn_id", updatable = false)
    private AiTurn turn;
    @Column(nullable = false, length = 16)
    private String role;
    @Column(nullable = false, columnDefinition = "text")
    private String content;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AiMessage() {
    }

    public AiMessage(AiConversation conversation, AiTurn turn, String role, String content) {
        this.conversation = conversation;
        this.turn = turn;
        this.role = role;
        this.content = content;
        createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getTurnId() { return turn == null ? null : turn.getId(); }
    public String getRole() { return role; }
    public String getContent() { return content; }
    public Instant getCreatedAt() { return createdAt; }
}
