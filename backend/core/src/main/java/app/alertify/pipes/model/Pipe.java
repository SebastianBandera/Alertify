package app.alertify.pipes.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.envers.AuditJoinTable;
import org.hibernate.envers.AuditTable;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

import app.alertify.jpa.entity.Tag;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

@Entity
@Audited
@AuditTable(value = "pipes_aud", schema = "audit")
@Table(name = "pipes", schema = "core", uniqueConstraints =
        @UniqueConstraint(name = "uq_pipes_name", columnNames = "name"))
public class Pipe {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @NotAudited
    @Column(nullable = false)
    private long version;

    @Column(nullable = false, columnDefinition = "text")
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "allow_concurrent_executions", nullable = false)
    private boolean allowConcurrentExecutions;

    @OneToMany(mappedBy = "pipe", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    private List<PipeStep> steps = new ArrayList<>();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "pipe_tag", schema = "core",
            joinColumns = @JoinColumn(name = "pipe_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id"))
    @AuditJoinTable(name = "pipe_tag_aud", schema = "audit")
    private Set<Tag> tags = new LinkedHashSet<>();

    @CreationTimestamp
    @NotAudited
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @NotAudited
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Pipe() {
    }

    public Pipe(String name, String description, boolean enabled, boolean allowConcurrentExecutions) {
        update(name, description, enabled, allowConcurrentExecutions);
    }

    public void update(String name, String description, boolean enabled, boolean allowConcurrentExecutions) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.description = description;
        this.enabled = enabled;
        this.allowConcurrentExecutions = allowConcurrentExecutions;
    }

    public void replaceSteps(List<PipeStep> values) {
        steps.clear();
        steps.addAll(Objects.requireNonNull(values, "steps must not be null"));
    }

    public void clearSteps() { steps.clear(); }
    public void replaceTags(Set<Tag> values) {
        tags.clear();
        if (values != null)
            tags.addAll(values);
    }
    public Long getId() { return id; }
    public long getVersion() { return version; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public boolean isEnabled() { return enabled; }
    public boolean isConcurrentExecutionAllowed() { return allowConcurrentExecutions; }
    public List<PipeStep> getSteps() { return Collections.unmodifiableList(steps); }
    public Set<Tag> getTags() { return Collections.unmodifiableSet(tags); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
