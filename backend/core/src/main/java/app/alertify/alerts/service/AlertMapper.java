package app.alertify.alerts.service;

import java.time.Duration;
import java.util.List;

import app.alertify.alerts.api.AlertExecutionResponse;
import app.alertify.alerts.api.AlertParameterValueResponse;
import app.alertify.alerts.api.AlertResponse;
import app.alertify.alerts.api.AlertTemplateParameterResponse;
import app.alertify.alerts.api.AlertTemplateResponse;
import app.alertify.alerts.model.Alert;
import app.alertify.alerts.model.AlertExecution;
import app.alertify.alerts.model.AlertParameterValue;
import app.alertify.alerts.model.AlertTemplateDefinition;
import app.alertify.alerts.model.AlertTemplateParameterDefinition;

final class AlertMapper {

    private AlertMapper() {
    }

    static AlertTemplateResponse toTemplate(AlertTemplateDefinition template, List<AlertTemplateParameterDefinition> parameters) {
        return new AlertTemplateResponse(
                template.getId(), template.getVersion(), template.getTemplateKey(),
                template.getNameKey(), template.getDescriptionKey(), template.getRequiredCapability(),
                parameters.stream().map(AlertMapper::toTemplateParameter).toList(),
                template.getCreatedAt(), template.getUpdatedAt()
        );
    }

    static AlertResponse toAlert(Alert alert, List<AlertParameterValue> values) {
        AlertTemplateDefinition template = alert.getTemplate();
        return new AlertResponse(
                alert.getId(), alert.getVersion(), template.getId(), template.getTemplateKey(),
                template.getNameKey(), alert.getName(), alert.getDescription(), alert.getCronExpression(),
                alert.isEnabled(), values.stream().map(AlertMapper::toParameterValue).toList(),
                alert.getCreatedAt(), alert.getUpdatedAt()
        );
    }

    static AlertExecutionResponse toExecution(AlertExecution execution) {
        return new AlertExecutionResponse(
                execution.getId(), execution.getAlert().getId(), execution.getAlert().getName(),
                execution.getStatus(), execution.getStartedAt(), execution.getFinishedAt(),
                Duration.between(execution.getStartedAt(), execution.getFinishedAt()).toMillis(),
                execution.getStatusMessage(), execution.getErrorType(), execution.getErrorMessage()
        );
    }

    private static AlertTemplateParameterResponse toTemplateParameter(AlertTemplateParameterDefinition parameter) {
        return new AlertTemplateParameterResponse(
                parameter.getId(), parameter.getVersion(), parameter.getParameterKey(),
                parameter.getLabelKey(), parameter.getDescriptionKey(), parameter.getJavaType(),
                parameter.getOptions(), parameter.isBindingAllowed(), parameter.getDefaultValue(),
                parameter.getParameterOrder(), parameter.isRequired(), parameter.getCreatedAt(),
                parameter.getUpdatedAt()
        );
    }

    private static AlertParameterValueResponse toParameterValue(AlertParameterValue value) {
        return new AlertParameterValueResponse(
                value.getId(), value.getVersion(), value.getTemplateParameter().getParameterKey(),
                value.getSource(), value.getTextValue(),
                value.getConfiguration() == null ? null : value.getConfiguration().getId(),
                value.getConfiguration() == null ? null : value.getConfiguration().getName(),
                value.getSecret() == null ? null : value.getSecret().getId(),
                value.getSecret() == null ? null : value.getSecret().getName(),
                value.getCreatedAt(), value.getUpdatedAt()
        );
    }
}
