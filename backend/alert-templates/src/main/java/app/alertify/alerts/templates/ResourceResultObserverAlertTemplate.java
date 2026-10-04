package app.alertify.alerts.templates;

import java.util.Set;

import app.alertify.alerts.AlertEvaluator;
import app.alertify.alerts.AlertExecutionContext;
import app.alertify.alerts.AlertResult;
import app.alertify.alerts.template.annotation.AlertParameter;
import app.alertify.alerts.template.annotation.AlertParameterSource;
import app.alertify.alerts.template.annotation.AlertTemplate;

/**
 * Evaluated by the backend against terminal history; it never invokes its target.
 */
@AlertTemplate(
    nameKey = "alerts.template.resourceObserver.name",
    descriptionKey = "alerts.template.resourceObserver.description",
    sourcePath = "app/alertify/alerts/templates/ResourceResultObserverAlertTemplate.java"
)
public final class ResourceResultObserverAlertTemplate implements AlertEvaluator {

    @AlertParameter(
        labelKey = "alerts.template.resourceObserver.kind",
        descriptionKey = "alerts.template.resourceObserver.kindDescription",
        options = { "PIPE", "PROCEDURE", "HOOK" },
        bindingAllowed = false,
        defaultValue = "PIPE",
        allowedSources = AlertParameterSource.TEXT,
        order = 1
    )
    private final String resourceKind;

    @AlertParameter(
        labelKey = "alerts.template.resourceObserver.id",
        descriptionKey = "alerts.template.resourceObserver.idDescription",
        allowedSources = AlertParameterSource.TEXT,
        order = 2
    )
    private final Long resourceId;

    public ResourceResultObserverAlertTemplate(String resourceKind, Long resourceId) {
        if (!Set.of("PIPE", "PROCEDURE", "HOOK").contains(resourceKind) || resourceId == null || resourceId <= 0)
            throw new IllegalArgumentException("A resource kind and positive id are required");

        this.resourceKind = resourceKind;
        this.resourceId = resourceId;
    }

    @Override
    public AlertResult evaluate(AlertExecutionContext context) {
        throw new IllegalStateException("Resource result observer must be evaluated by the backend: " + resourceKind + ":" + resourceId);
    }
}
