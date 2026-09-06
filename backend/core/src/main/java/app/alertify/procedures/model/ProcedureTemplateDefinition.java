package app.alertify.procedures.model;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.envers.AuditTable;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;
import org.hibernate.type.SqlTypes;

import app.alertify.procedures.template.annotation.ProcedureTemplate;
import app.alertify.procedures.template.annotation.ProcedureTemplateKey;
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
 * Persistent catalog entry synchronized from one class annotated as a procedure
 * template. Its stable alternate key is the template class fully qualified
 * name, while persistence uses a generated numeric primary key. The entry also
 * carries the worker capability the template requires and whether its result is
 * sensitive and must not be stored.
 */
@Entity
@Audited
@AuditTable(value = "procedure_templates_aud", schema = "audit")
@Table(name = "procedure_templates", schema = "core", uniqueConstraints =
        @UniqueConstraint(name = "uq_procedure_templates_template_key", columnNames = "template_key"))
public class ProcedureTemplateDefinition {

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

    @Column(name = "source_path", nullable = false, columnDefinition = "text")
    private String sourcePath;

    @Enumerated(EnumType.STRING)
    @Column(name = "required_capability", nullable = false, length = 32)
    private WorkerCapability requiredCapability;

    @Column(name = "sensitive_result", nullable = false)
    private boolean sensitiveResult;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<ProcedureTemplateTagDefinition> tags = List.of();

    @CreationTimestamp
    @NotAudited
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @NotAudited
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProcedureTemplateDefinition() {
    }

    public ProcedureTemplateDefinition(String templateKey, String nameKey, String descriptionKey,
            String sourcePath, WorkerCapability requiredCapability, boolean sensitiveResult,
            List<ProcedureTemplateTagDefinition> tags) {
        this.templateKey = Objects.requireNonNull(templateKey, "templateKey must not be null");
        synchronize(nameKey, descriptionKey, sourcePath, requiredCapability, sensitiveResult, tags);
    }

    public static ProcedureTemplateDefinition from(Class<?> templateClass) {
        ProcedureTemplate metadata = templateClass.getAnnotation(ProcedureTemplate.class);
        return new ProcedureTemplateDefinition(
                ProcedureTemplateKey.of(templateClass), metadata.nameKey(), metadata.descriptionKey(),
                metadata.sourcePath(), metadata.capability(), metadata.sensitiveResult(),
                Arrays.stream(metadata.tags()).map(ProcedureTemplateTagDefinition::from).toList()
        );
    }

    public void synchronize(String nameKey, String descriptionKey, String sourcePath, WorkerCapability requiredCapability, boolean sensitiveResult, List<ProcedureTemplateTagDefinition> tags) {
        this.nameKey = Objects.requireNonNull(nameKey, "nameKey must not be null");
        this.descriptionKey = Objects.requireNonNull(descriptionKey, "descriptionKey must not be null");
        this.sourcePath = Objects.requireNonNull(sourcePath, "sourcePath must not be null");
        this.requiredCapability = Objects.requireNonNull(requiredCapability, "requiredCapability must not be null");
        this.sensitiveResult = sensitiveResult;
        this.tags = List.copyOf(Objects.requireNonNull(tags, "tags must not be null"));
    }

    public Long getId() { return id; }
    public long getVersion() { return version; }
    public String getTemplateKey() { return templateKey; }
    public String getNameKey() { return nameKey; }
    public String getDescriptionKey() { return descriptionKey; }
    public String getSourcePath() { return sourcePath; }
    public WorkerCapability getRequiredCapability() { return requiredCapability; }
    public boolean isSensitiveResult() { return sensitiveResult; }
    public List<ProcedureTemplateTagDefinition> getTags() { return tags; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
