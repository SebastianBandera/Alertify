package app.alertify.pipes.model;

import java.util.Objects;

import org.hibernate.envers.AuditTable;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

import app.alertify.alerts.model.AlertTemplateParameterDefinition;
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_parameter_id")
    private ProcedureTemplateParameterDefinition targetProcedureParameter;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_alert_parameter_id")
    private AlertTemplateParameterDefinition targetAlertParameter;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_step_id", nullable = false)
    private PipeStep sourceStep;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_output_id")
    private ProcedureTemplateOutputDefinition sourceOutput;

    @Column(name = "source_result_pointer", columnDefinition = "text")
    private String sourceResultPointer;

    @Column(name = "value_expression", columnDefinition = "text")
    private String valueExpression;

    protected PipeStepBinding() {
    }

    public PipeStepBinding(PipeStep targetStep, ProcedureTemplateParameterDefinition targetParameter, PipeStep sourceStep, ProcedureTemplateOutputDefinition sourceOutput) {
        this.targetStep = Objects.requireNonNull(targetStep);
        this.targetProcedureParameter = Objects.requireNonNull(targetParameter);
        this.sourceStep = Objects.requireNonNull(sourceStep);
        this.sourceOutput = Objects.requireNonNull(sourceOutput);
    }

    public PipeStepBinding(PipeStep targetStep, ProcedureTemplateParameterDefinition targetParameter, PipeStep sourceStep, String sourceResultPointer) {
        this(targetStep, targetParameter, sourceStep, sourceResultPointer, null);
    }

    public PipeStepBinding(PipeStep targetStep, ProcedureTemplateParameterDefinition targetParameter, PipeStep sourceStep, String sourceResultPointer, String valueExpression) {
        this.targetStep = Objects.requireNonNull(targetStep);
        this.targetProcedureParameter = Objects.requireNonNull(targetParameter);
        this.sourceStep = Objects.requireNonNull(sourceStep);
        this.sourceResultPointer = Objects.requireNonNull(sourceResultPointer);
        this.valueExpression = valueExpression;
    }

    public PipeStepBinding(PipeStep targetStep, AlertTemplateParameterDefinition targetParameter, PipeStep sourceStep, String sourceResultPointer, String valueExpression) {
        this.targetStep = Objects.requireNonNull(targetStep);
        this.targetAlertParameter = Objects.requireNonNull(targetParameter);
        this.sourceStep = Objects.requireNonNull(sourceStep);
        this.sourceResultPointer = Objects.requireNonNull(sourceResultPointer);
        this.valueExpression = valueExpression;
    }

    public Long getId() { return id; }
    public PipeStep getTargetStep() { return targetStep; }
    public ProcedureTemplateParameterDefinition getTargetParameter() { return targetProcedureParameter; }
    public ProcedureTemplateParameterDefinition getTargetProcedureParameter() { return targetProcedureParameter; }
    public AlertTemplateParameterDefinition getTargetAlertParameter() { return targetAlertParameter; }
    public String getTargetParameterKey() { return targetProcedureParameter == null ? targetAlertParameter.getParameterKey() : targetProcedureParameter.getParameterKey(); }
    public boolean isAlertTarget() { return targetAlertParameter != null; }
    public PipeStep getSourceStep() { return sourceStep; }
    public ProcedureTemplateOutputDefinition getSourceOutput() { return sourceOutput; }
    public String getSourceResultPointer() { return sourceResultPointer; }
    public String getValueExpression() { return valueExpression; }
    public boolean isArtifactOutput() { return sourceOutput != null; }
}
