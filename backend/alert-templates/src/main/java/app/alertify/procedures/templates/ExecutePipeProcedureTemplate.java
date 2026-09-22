package app.alertify.procedures.templates;

import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.pipes.Pipe;
import app.alertify.procedures.ProcedureEvaluator;
import app.alertify.procedures.ProcedureExecutionContext;
import app.alertify.procedures.template.annotation.ProcedureParameter;
import app.alertify.procedures.template.annotation.ProcedureTemplate;
import app.alertify.procedures.template.annotation.ProcedureTemplateTag;
import tools.jackson.databind.JsonNode;

@ProcedureTemplate(
    nameKey = "procedures.template.executePipe.name",
    descriptionKey = "procedures.template.executePipe.description",
    tags = @ProcedureTemplateTag(nameKey = "procedures.templateTag.configuration", color = "#16A34A"),
    sourcePath = "app/alertify/procedures/templates/ExecutePipeProcedureTemplate.java"
)
public final class ExecutePipeProcedureTemplate implements ProcedureEvaluator {

    @ProcedureParameter(
        labelKey = "procedures.template.executePipe.pipe",
        descriptionKey = "procedures.template.executePipe.pipeDescription",
        allowedSources = AlertParameterSource.PIPE,
        order = 1
    )
    private final Pipe pipe;

    public ExecutePipeProcedureTemplate(Pipe pipe) {
        this.pipe = pipe;
    }

    @Override
    public JsonNode execute(ProcedureExecutionContext context) {
        return pipe.execute();
    }
}
