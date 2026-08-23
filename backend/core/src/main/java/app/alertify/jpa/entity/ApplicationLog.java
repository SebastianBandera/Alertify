package app.alertify.jpa.entity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import app.alertify.logging.ApplicationLogLevel;
import app.alertify.logging.ApplicationLogOutcome;
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
import tools.jackson.databind.JsonNode;

@Entity
@Table(name = "logs", schema = "audit")
public class ApplicationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_at", nullable = false, updatable = false)
    private Instant eventAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "level_id", nullable = false, updatable = false)
    private ApplicationLogLevelDefinition level;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_id", nullable = false, updatable = false)
    private ApplicationLogSource source;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false, updatable = false)
    private ApplicationLogEvent event;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private ApplicationLogOutcome outcome;

    @Column(name = "user_subject", nullable = false, columnDefinition = "text", updatable = false)
    private String userSubject;

    @Column(nullable = false, columnDefinition = "text", updatable = false)
    private String username;

    @Column(name = "request_id", updatable = false)
    private UUID requestId;

    @Column(columnDefinition = "text", updatable = false)
    private String path;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb", updatable = false)
    private JsonNode data;

    protected ApplicationLog() {
    }

    public ApplicationLog(
            Instant eventAt,
            ApplicationLogLevelDefinition level,
            ApplicationLogSource source,
            ApplicationLogEvent event,
            ApplicationLogOutcome outcome,
            String userSubject,
            String username,
            UUID requestId,
            String path,
            JsonNode data) {
        this.eventAt = Objects.requireNonNull(eventAt);
        this.level = Objects.requireNonNull(level);
        this.source = Objects.requireNonNull(source);
        this.event = Objects.requireNonNull(event);
        this.outcome = Objects.requireNonNull(outcome);
        this.userSubject = Objects.requireNonNull(userSubject);
        this.username = Objects.requireNonNull(username);
        this.requestId = requestId;
        this.path = path;
        this.data = Objects.requireNonNull(data);
    }

    public Long getId() {
        return id;
    }

    public Instant getEventAt() {
        return eventAt;
    }

    public ApplicationLogLevel getLevel() {
        return ApplicationLogLevel.valueOf(level.getCode());
    }

    public String getSource() {
        return source.getCode();
    }

    public String getEvent() {
        return event.getCode();
    }

    public ApplicationLogOutcome getOutcome() {
        return outcome;
    }

    public String getUserSubject() {
        return userSubject;
    }

    public String getUsername() {
        return username;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public String getPath() {
        return path;
    }

    public JsonNode getData() {
        return data;
    }
}
