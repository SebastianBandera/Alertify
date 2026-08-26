package app.alertify.alerts.model;

import java.time.Instant;
import java.util.Objects;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.envers.AuditTable;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

import app.alertify.alerts.template.annotation.AlertTemplate;
import app.alertify.alerts.template.annotation.AlertTemplateIdentifier;
import app.alertify.worker.contract.WorkerCapability;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Persistent catalog entry synchronized from one class annotated as an alert
 * template. Its identifier is the template class fully qualified name.
 */
@Entity
@Audited
@AuditTable(value = "alert_templates_aud", schema = "audit")
@Table(name = "alert_templates", schema = "core")
public class AlertTemplateDefinition {

    @Id
    @Column(columnDefinition = "text", updatable = false)
    private String id;

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

    public AlertTemplateDefinition(String id, String nameKey, String descriptionKey, WorkerCapability requiredCapability) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        synchronize(nameKey, descriptionKey, requiredCapability);
    }

    public static AlertTemplateDefinition from(Class<?> templateClass) {
        String id = AlertTemplateIdentifier.of(templateClass);
        AlertTemplate metadata = templateClass.getAnnotation(AlertTemplate.class);
        return new AlertTemplateDefinition(
                id, metadata.nameKey(), metadata.descriptionKey(), metadata.capability()
        );
    }

    public String getId() {
        return id;
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
