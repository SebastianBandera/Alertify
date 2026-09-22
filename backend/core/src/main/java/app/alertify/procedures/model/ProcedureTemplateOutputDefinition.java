package app.alertify.procedures.model;

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

@Entity
@Audited
@AuditTable(value = "procedure_template_outputs_aud", schema = "audit")
@Table(name = "procedure_template_outputs", schema = "core", uniqueConstraints =
        @UniqueConstraint(name = "uq_procedure_template_outputs_template_key",
                columnNames = { "procedure_template_id", "output_key" }))
public class ProcedureTemplateOutputDefinition {
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

    @Column(name = "output_key", nullable = false, columnDefinition = "text", updatable = false)
    private String outputKey;

    @Column(name = "output_order", nullable = false)
    private int outputOrder;

    @CreationTimestamp
    @NotAudited
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @NotAudited
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProcedureTemplateOutputDefinition() {
    }

    public ProcedureTemplateOutputDefinition(ProcedureTemplateDefinition template, String outputKey, int outputOrder) {
        this.template = Objects.requireNonNull(template, "template must not be null");
        this.outputKey = Objects.requireNonNull(outputKey, "outputKey must not be null");
        synchronize(outputOrder);
    }

    public void synchronize(int value) {
        if (value < 0)
            throw new IllegalArgumentException("outputOrder must not be negative");

        outputOrder = value;
    }

    public Long getId() { return id; }
    public long getVersion() { return version; }
    public ProcedureTemplateDefinition getTemplate() { return template; }
    public String getOutputKey() { return outputKey; }
    public int getOutputOrder() { return outputOrder; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
