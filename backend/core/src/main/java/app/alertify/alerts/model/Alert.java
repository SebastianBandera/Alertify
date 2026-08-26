package app.alertify.alerts.model;

import java.time.Instant;
import java.util.Objects;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.envers.AuditTable;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

/**
 * User-configured alert based on one registered template.
 */
@Entity
@Audited
@AuditTable(value = "alerts_aud", schema = "audit")
@Table(
    name = "alerts",
    schema = "core",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_alerts_name",
        columnNames = "name"
    )
)
public class Alert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @NotAudited
    @Column(nullable = false)
    private long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "alert_template_id", nullable = false, columnDefinition = "text", updatable = false)
    private AlertTemplateDefinition template;

    @Column(nullable = false, columnDefinition = "text")
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "cron_expression", nullable = false, columnDefinition = "text")
    private String cronExpression;

    @Column(nullable = false)
    private boolean enabled;

    @CreationTimestamp
    @NotAudited
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @NotAudited
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Alert() {
    }

    public Alert(AlertTemplateDefinition template, String name, String description, String cronExpression, boolean enabled) {
        this.template = Objects.requireNonNull(template, "template must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.description = description;
        this.cronExpression = Objects.requireNonNull(cronExpression, "cronExpression must not be null");
        this.enabled = enabled;
    }

    public Long getId() {
        return id;
    }

    public long getVersion() {
        return version;
    }

    public AlertTemplateDefinition getTemplate() {
        return template;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getCronExpression() {
        return cronExpression;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void rename(String name) {
        this.name = Objects.requireNonNull(name, "name must not be null");
    }

    public void changeDescription(String description) {
        this.description = description;
    }

    public void reschedule(String cronExpression) {
        this.cronExpression = Objects.requireNonNull(cronExpression, "cronExpression must not be null");
    }

    public void enable() {
        enabled = true;
    }

    public void disable() {
        enabled = false;
    }

}
