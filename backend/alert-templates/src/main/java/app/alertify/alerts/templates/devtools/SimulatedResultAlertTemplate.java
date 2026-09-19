package app.alertify.alerts.templates.devtools;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import app.alertify.alerts.AlertEvaluator;
import app.alertify.alerts.AlertExecutionContext;
import app.alertify.alerts.AlertResult;
import app.alertify.alerts.template.annotation.AlertParameter;
import app.alertify.alerts.template.annotation.AlertTemplate;
import app.alertify.alerts.template.annotation.AlertTemplateTag;

/**
 * Development template whose outcome is chosen by configuration, to exercise
 * the dashboard, notifications and hooks without a real check behind them.
 * ERROR is reserved for exceptions in the execution model, so that outcome is
 * produced by throwing {@link SimulatedFailure} with the configured message.
 */
@AlertTemplate(
    nameKey = "alerts.template.devtools.simulatedResult.name",
    descriptionKey = "alerts.template.devtools.simulatedResult.description",
    tags = @AlertTemplateTag(nameKey = "alerts.templateTag.development"),
    sourcePath = "app/alertify/alerts/templates/devtools/SimulatedResultAlertTemplate.java"
)
public final class SimulatedResultAlertTemplate implements AlertEvaluator {

    private static final String SUCCESS = "SUCCESS";
    private static final String WARN = "WARN";
    private static final String ERROR = "ERROR";
    private static final Set<String> STATUSES = Set.of(SUCCESS, WARN, ERROR);

    @AlertParameter(
        labelKey = "alerts.template.devtools.simulatedResult.status",
        descriptionKey = "alerts.template.devtools.simulatedResult.statusDescription",
        options = { SUCCESS, WARN, ERROR },
        bindingAllowed = false,
        defaultValue = SUCCESS,
        order = 1
    )
    private final String status;

    @AlertParameter(
        labelKey = "alerts.template.devtools.simulatedResult.message",
        descriptionKey = "alerts.template.devtools.simulatedResult.messageDescription",
        defaultValue = "Simulated result",
        multiline = true,
        required = false,
        order = 2
    )
    private final String message;

    public SimulatedResultAlertTemplate(String status, String message) {
        if (status == null || !STATUSES.contains(status))
            throw new IllegalArgumentException("status must be one of " + STATUSES);

        this.status = status;
        this.message = message == null ? "" : message;
    }

    @Override
    public AlertResult evaluate(AlertExecutionContext context) {
        if (ERROR.equals(status))
            throw new SimulatedFailure(message);

        Map<String, Object> statusMessage = new LinkedHashMap<>();
        statusMessage.put("status", status);
        statusMessage.put("message", message);
        return WARN.equals(status) ? AlertResult.warn(statusMessage) : AlertResult.success(statusMessage);
    }

    /** Failure raised on purpose; its type in the execution history tells it apart from a real one. */
    public static final class SimulatedFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public SimulatedFailure(String message) {
            super(message);
        }
    }
}
