package app.alertify.alerts.templates.devtools;

import java.util.Map;

import app.alertify.alerts.AlertEvaluator;
import app.alertify.alerts.AlertExecutionContext;
import app.alertify.alerts.AlertResult;
import app.alertify.alerts.template.annotation.AlertParameter;
import app.alertify.alerts.template.annotation.AlertTemplate;
import app.alertify.alerts.template.annotation.AlertTemplateTag;

/**
 * Development template that copies a source value into a mutable parameter.
 */
@AlertTemplate(
    nameKey = "alerts.template.devtools.writableParameterCopy.name",
    descriptionKey = "alerts.template.devtools.writableParameterCopy.description",
    tags = {
        @AlertTemplateTag(nameKey = "alerts.templateTag.development"),
        @AlertTemplateTag(nameKey = "alerts.templateTag.configuration", color = "#16A34A")
    },
    sourcePath = "app/alertify/alerts/templates/devtools/WritableParameterCopyAlertTemplate.java"
)
public final class WritableParameterCopyAlertTemplate implements AlertEvaluator {

    @AlertParameter(
        labelKey = "alerts.template.devtools.writableParameterCopy.sourceValue",
        descriptionKey = "alerts.template.devtools.writableParameterCopy.sourceValueDescription",
        order = 1
    )
    private final String sourceValue;

    @AlertParameter(
        labelKey = "alerts.template.devtools.writableParameterCopy.writableValue",
        descriptionKey = "alerts.template.devtools.writableParameterCopy.writableValueDescription",
        order = 2
    )
    private String writableValue;

    public WritableParameterCopyAlertTemplate(String sourceValue, String writableValue) {
        this.sourceValue = sourceValue;
        this.writableValue = writableValue;
    }

    @Override
    public AlertResult evaluate(AlertExecutionContext context) {
        writableValue = sourceValue;
        return AlertResult.success(Map.of("writtenValue", writableValue));
    }
}
