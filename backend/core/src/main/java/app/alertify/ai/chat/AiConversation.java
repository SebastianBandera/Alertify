package app.alertify.ai.chat;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "ai_conversations", schema = "core")
public class AiConversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_subject", nullable = false, updatable = false, columnDefinition = "text")
    private String userSubject;
    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String username;
    @Column(nullable = false, columnDefinition = "text")
    private String title;
    @Column(nullable = false, length = 16)
    private String mode;
    @Column(name = "approval_policy", nullable = false, length = 16)
    private String approvalPolicy;
    @Column(nullable = false, length = 16)
    private String status;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AiConversation() {
    }

    public AiConversation(String userSubject, String username, String title, String mode, String approvalPolicy) {
        this.userSubject = userSubject;
        this.username = username;
        this.title = title;
        this.mode = mode;
        this.approvalPolicy = approvalPolicy;
        status = "ACTIVE";
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    public Long getId() { return id; }
    public String getUserSubject() { return userSubject; }
    public String getUsername() { return username; }
    public String getTitle() { return title; }
    public String getMode() { return mode; }
    public String getApprovalPolicy() { return approvalPolicy; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void configure(String nextMode, String nextApprovalPolicy) {
        mode = nextMode;
        approvalPolicy = nextApprovalPolicy;
        updatedAt = Instant.now();
    }

    public void touch() {
        updatedAt = Instant.now();
    }
}
