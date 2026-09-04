package app.alertify.alerts.templates.devtools;

import java.util.Map;

import app.alertify.alerts.AlertEvaluator;
import app.alertify.alerts.AlertExecutionContext;
import app.alertify.alerts.AlertResult;
import app.alertify.alerts.template.annotation.AlertParameter;
import app.alertify.alerts.template.annotation.AlertTemplate;
import app.alertify.alerts.template.annotation.AlertTemplateTag;

/**
 * Development template that prints its configured value to standard output.
 */
@AlertTemplate(
    nameKey = "alerts.template.devtools.consoleParameter.name",
    descriptionKey = "alerts.template.devtools.consoleParameter.description",
    tags = @AlertTemplateTag(nameKey = "alerts.templateTag.development"),
    sourcePath = "app/alertify/alerts/templates/devtools/ConsoleParameterAlertTemplate.java"
)
public final class ConsoleParameterAlertTemplate implements AlertEvaluator {

    @AlertParameter(
        labelKey = "alerts.template.devtools.consoleParameter.value",
        descriptionKey = "alerts.template.devtools.consoleParameter.valueDescription",
        order = 1
    )
    private final String value;

    public ConsoleParameterAlertTemplate(String value) {
        this.value = value;
    }

    @Override
    public AlertResult evaluate(AlertExecutionContext context) {
        System.out.println("ConsoleParameterAlertTemplate value: " + value);
        return AlertResult.success(Map.of("printedValue", value));
    }
}
