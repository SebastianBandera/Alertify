package app.alertify.procedures.templates.devtools;

import app.alertify.procedures.ProcedureEvaluator;
import app.alertify.procedures.ProcedureExecutionContext;
import app.alertify.procedures.template.annotation.ProcedureParameter;
import app.alertify.procedures.template.annotation.ProcedureTemplate;
import app.alertify.procedures.template.annotation.ProcedureTemplateTag;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Development template that prints its configured value to standard output. */
@ProcedureTemplate(
    nameKey = "procedures.template.devtools.consoleParameter.name",
    descriptionKey = "procedures.template.devtools.consoleParameter.description",
    tags = @ProcedureTemplateTag(nameKey = "procedures.templateTag.development"),
    sourcePath = "app/alertify/procedures/templates/devtools/ConsoleParameterProcedureTemplate.java"
)
public final class ConsoleParameterProcedureTemplate implements ProcedureEvaluator {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @ProcedureParameter(
        labelKey = "procedures.template.devtools.consoleParameter.value",
        descriptionKey = "procedures.template.devtools.consoleParameter.valueDescription",
        order = 1
    )
    private final String value;

    public ConsoleParameterProcedureTemplate(String value) {
        this.value = value;
    }

    @Override
    public JsonNode execute(ProcedureExecutionContext context) {
        System.out.println("ConsoleParameterProcedureTemplate value: " + value);
        return JSON.createObjectNode().put("printedValue", value);
    }
}
