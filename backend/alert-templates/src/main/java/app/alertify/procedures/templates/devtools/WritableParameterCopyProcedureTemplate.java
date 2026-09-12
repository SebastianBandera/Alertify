package app.alertify.procedures.templates.devtools;

import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.procedures.ProcedureEvaluator;
import app.alertify.procedures.ProcedureExecutionContext;
import app.alertify.procedures.template.annotation.ProcedureParameter;
import app.alertify.procedures.template.annotation.ProcedureTemplate;
import app.alertify.procedures.template.annotation.ProcedureTemplateTag;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Development template that copies a source value into a mutable parameter. */
@ProcedureTemplate(
    nameKey = "procedures.template.devtools.writableParameterCopy.name",
    descriptionKey = "procedures.template.devtools.writableParameterCopy.description",
    tags = {
        @ProcedureTemplateTag(nameKey = "procedures.templateTag.development"),
        @ProcedureTemplateTag(nameKey = "procedures.templateTag.configuration", color = "#16A34A")
    },
    sourcePath = "app/alertify/procedures/templates/devtools/WritableParameterCopyProcedureTemplate.java"
)
public final class WritableParameterCopyProcedureTemplate implements ProcedureEvaluator {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @ProcedureParameter(
        labelKey = "procedures.template.devtools.writableParameterCopy.sourceValue",
        descriptionKey = "procedures.template.devtools.writableParameterCopy.sourceValueDescription",
        order = 1
    )
    private final String sourceValue;

    @ProcedureParameter(
        labelKey = "procedures.template.devtools.writableParameterCopy.writableValue",
        descriptionKey = "procedures.template.devtools.writableParameterCopy.writableValueDescription",
        order = 2
    )
    private String writableValue;

    public WritableParameterCopyProcedureTemplate(String sourceValue, String writableValue) {
        this.sourceValue = sourceValue;
        this.writableValue = writableValue;
    }

    @Override
    public JsonNode execute(ProcedureExecutionContext context) {
        writableValue = sourceValue;
        if (context.getParameterSource("sourceValue") == AlertParameterSource.SECRET
                || context.getParameterSource("writableValue") == AlertParameterSource.SECRET)
            return JSON.createObjectNode();

        return JSON.createObjectNode().put("writtenValue", writableValue);
    }
}
