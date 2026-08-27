package app.alertify.alerts.model;

import java.time.Instant;
import java.util.Objects;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.envers.AuditTable;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

import app.alertify.alerts.template.annotation.AlertTemplate;
import app.alertify.alerts.template.annotation.AlertTemplateKey;
import app.alertify.worker.contract.WorkerCapability;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

/**
 * Persistent catalog entry synchronized from one class annotated as an alert
 * template. Its stable alternate key is the template class fully qualified
 * name, while persistence uses a generated numeric primary key.
 */
@Entity
@Audited
@AuditTable(value = "alert_templates_aud", schema = "audit")
@Table(
    name = "alert_templates",
    schema = "core",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_alert_templates_template_key",
        columnNames = "template_key"
    )
)
public class AlertTemplateDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "template_key", nullable = false, columnDefinition = "text", updatable = false)
    private String templateKey;

    @Version
    @NotAudited
    @Column(nullable = false)
    private long version;

    @Column(name = "name_key", nullable = false, columnDefinition = "text")
    private String nameKey;

    @Column(name = "description_key", nullable = false, columnDefinition = "text")
    private String descriptionKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "required_capability", nullable = false, length = 32)
    private WorkerCapability requiredCapability;

    @CreationTimestamp
    @NotAudited
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @NotAudited
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AlertTemplateDefinition() {
    }

    public AlertTemplateDefinition(String templateKey, String nameKey, String descriptionKey, WorkerCapability requiredCapability) {
        this.templateKey = Objects.requireNonNull(templateKey, "templateKey must not be null");
        synchronize(nameKey, descriptionKey, requiredCapability);
    }

    public static AlertTemplateDefinition from(Class<?> templateClass) {
        String templateKey = AlertTemplateKey.of(templateClass);
        AlertTemplate metadata = templateClass.getAnnotation(AlertTemplate.class);
        return new AlertTemplateDefinition(
                templateKey, metadata.nameKey(), metadata.descriptionKey(), metadata.capability()
        );
    }

    public Long getId() {
        return id;
    }

    public String getTemplateKey() {
        return templateKey;
    }

    public long getVersion() {
        return version;
    }

    public String getNameKey() {
        return nameKey;
    }

    public String getDescriptionKey() {
        return descriptionKey;
    }

    public WorkerCapability getRequiredCapability() {
        return requiredCapability;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void synchronize(String nameKey, String descriptionKey, WorkerCapability requiredCapability) {
        this.nameKey = Objects.requireNonNull(nameKey, "nameKey must not be null");
        this.descriptionKey = Objects.requireNonNull(descriptionKey, "descriptionKey must not be null");
        this.requiredCapability = Objects.requireNonNull(requiredCapability, "requiredCapability must not be null");
    }
}
