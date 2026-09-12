package app.alertify.procedures.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.envers.AuditJoinTable;
import org.hibernate.envers.AuditTable;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

import app.alertify.jpa.entity.Tag;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

/**
 * User-configured procedure based on one registered template. A procedure
 * produces a value for an alert or for another procedure instead of raising an
 * alert of its own.
 */
@Entity
@Audited
@AuditTable(value = "procedures_aud", schema = "audit")
@Table(name = "procedures", schema = "core", uniqueConstraints =
        @UniqueConstraint(name = "uq_procedures_name", columnNames = "name"))
public class Procedure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @NotAudited
    @Column(nullable = false)
    private long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "procedure_template_id", nullable = false, updatable = false)
    private ProcedureTemplateDefinition template;

    @Column(nullable = false, columnDefinition = "text")
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "cron_expression", nullable = false, columnDefinition = "text")
    private String cronExpression;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "allow_concurrent_executions", nullable = false)
    private boolean allowConcurrentExecutions;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "procedure_tag", schema = "core",
            joinColumns = @JoinColumn(name = "procedure_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id"))
    @AuditJoinTable(name = "procedure_tag_aud", schema = "audit")
    private Set<Tag> tags = new LinkedHashSet<>();

    @CreationTimestamp
    @NotAudited
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @NotAudited
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Procedure() {
    }

    public Procedure(ProcedureTemplateDefinition template, String name, String description, String cronExpression, boolean enabled, boolean allowConcurrentExecutions, Set<Tag> tags) {
        this.template = Objects.requireNonNull(template, "template must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.description = description;
        this.cronExpression = Objects.requireNonNull(cronExpression, "cronExpression must not be null");
        this.enabled = enabled;
        this.allowConcurrentExecutions = allowConcurrentExecutions;
        replaceTags(tags);
    }

    public Long getId() { return id; }
    public long getVersion() { return version; }
    public ProcedureTemplateDefinition getTemplate() { return template; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getCronExpression() { return cronExpression; }
    public boolean isEnabled() { return enabled; }
    public boolean isConcurrentExecutionAllowed() { return allowConcurrentExecutions; }
    public Set<Tag> getTags() { return Collections.unmodifiableSet(tags); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void rename(String value) { name = Objects.requireNonNull(value, "name must not be null"); }
    public void changeDescription(String value) { description = value; }
    public void reschedule(String value) { cronExpression = Objects.requireNonNull(value, "cronExpression must not be null"); }
    public void enable() { enabled = true; }
    public void disable() { enabled = false; }
    public void changeConcurrentExecution(boolean value) { allowConcurrentExecutions = value; }
    public void replaceTags(Set<Tag> values) {
        tags.clear();
        if (values != null)
            tags.addAll(values);
    }
}
