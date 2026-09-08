package app.alertify.hooks.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.envers.AuditTable;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

import app.alertify.jpa.entity.ApplicationSecret;
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
import jakarta.persistence.Version;

@Entity
@Audited
@AuditTable(value = "hooks_aud", schema = "audit")
@Table(name = "hooks", schema = "core")
public class Hook {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @NotAudited
    @Column(nullable = false)
    private long version;

    @Column(name = "public_id", nullable = false)
    private UUID publicId;

    @Column(nullable = false, columnDefinition = "text")
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false)
    private boolean enabled;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private HookMode mode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "token_secret_id")
    private ApplicationSecret tokenSecret;

    @Column(name = "max_concurrent_invocations")
    private Integer maxConcurrentInvocations;

    @Column(name = "rate_limit_count")
    private Integer rateLimitCount;

    @Column(name = "rate_limit_window_seconds")
    private Long rateLimitWindowSeconds;

    @OneToMany(mappedBy = "hook", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    private List<HookTarget> targets = new ArrayList<>();

    @CreationTimestamp
    @NotAudited
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @NotAudited
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Hook() {
    }

    public Hook(String name, String description, HookMode mode, ApplicationSecret tokenSecret, Integer maxConcurrentInvocations, Integer rateLimitCount, Long rateLimitWindowSeconds) {
        publicId = UUID.randomUUID();
        this.name = Objects.requireNonNull(name);
        this.description = description;
        this.mode = Objects.requireNonNull(mode);
        this.tokenSecret = tokenSecret;
        this.maxConcurrentInvocations = maxConcurrentInvocations;
        this.rateLimitCount = rateLimitCount;
        this.rateLimitWindowSeconds = rateLimitWindowSeconds;
    }

    public Long getId() { return id; }
    public long getVersion() { return version; }
    public UUID getPublicId() { return publicId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public boolean isEnabled() { return enabled; }
    public HookMode getMode() { return mode; }
    public ApplicationSecret getTokenSecret() { return tokenSecret; }
    public Integer getMaxConcurrentInvocations() { return maxConcurrentInvocations; }
    public Integer getRateLimitCount() { return rateLimitCount; }
    public Long getRateLimitWindowSeconds() { return rateLimitWindowSeconds; }
    public List<HookTarget> getTargets() { return Collections.unmodifiableList(targets); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void update(String name, String description, boolean enabled, HookMode mode, ApplicationSecret tokenSecret, Integer maxConcurrentInvocations, Integer rateLimitCount, Long rateLimitWindowSeconds) {
        this.name = Objects.requireNonNull(name);
        this.description = description;
        this.enabled = enabled;
        this.mode = Objects.requireNonNull(mode);
        this.tokenSecret = tokenSecret;
        this.maxConcurrentInvocations = maxConcurrentInvocations;
        this.rateLimitCount = rateLimitCount;
        this.rateLimitWindowSeconds = rateLimitWindowSeconds;
    }

    public void replaceTargets(List<HookTarget> values) {
        targets.clear();
        targets.addAll(values);
    }

    public void clearTargets() { targets.clear(); }

    public void rotatePublicId() { publicId = UUID.randomUUID(); }
}
