package app.alertify.pipes.model;

import java.util.Objects;

import org.hibernate.envers.AuditTable;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

import app.alertify.procedures.model.ProcedureTemplateOutputDefinition;
import app.alertify.procedures.model.ProcedureTemplateParameterDefinition;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Audited
@AuditTable(value = "pipe_step_bindings_aud", schema = "audit")
@Table(name = "pipe_step_bindings", schema = "core")
public class PipeStepBinding {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @NotAudited
    @Column(nullable = false)
    private long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_step_id", nullable = false)
    private PipeStep targetStep;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_parameter_id", nullable = false)
    private ProcedureTemplateParameterDefinition targetParameter;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_step_id", nullable = false)
    private PipeStep sourceStep;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_output_id", nullable = false)
    private ProcedureTemplateOutputDefinition sourceOutput;

    protected PipeStepBinding() {
    }

    public PipeStepBinding(PipeStep targetStep, ProcedureTemplateParameterDefinition targetParameter, PipeStep sourceStep, ProcedureTemplateOutputDefinition sourceOutput) {
        this.targetStep = Objects.requireNonNull(targetStep);
        this.targetParameter = Objects.requireNonNull(targetParameter);
        this.sourceStep = Objects.requireNonNull(sourceStep);
        this.sourceOutput = Objects.requireNonNull(sourceOutput);
    }

    public Long getId() { return id; }
    public PipeStep getTargetStep() { return targetStep; }
    public ProcedureTemplateParameterDefinition getTargetParameter() { return targetParameter; }
    public PipeStep getSourceStep() { return sourceStep; }
    public ProcedureTemplateOutputDefinition getSourceOutput() { return sourceOutput; }
}
