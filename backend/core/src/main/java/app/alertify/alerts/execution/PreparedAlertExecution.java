package app.alertify.alerts.execution;

import java.util.List;

import app.alertify.alerts.AlertExecutionValue;
import app.alertify.worker.contract.WorkerCapability;

public record PreparedAlertExecution(
    long alertId,
    String alertName,
    String templateClassName,
    WorkerCapability requiredCapability,
    String sourceChecksum,
    String source,
    String state,
    List<ResolvedAlertParameter> parameters,
    List<AlertExecutionValue> preparedValues
) {

    public PreparedAlertExecution(long alertId, String alertName, String templateClassName, WorkerCapability requiredCapability, String sourceChecksum, String source, String state, List<ResolvedAlertParameter> parameters) {
        this(alertId, alertName, templateClassName, requiredCapability, sourceChecksum, source, state, parameters, List.of());
    }

    public PreparedAlertExecution {
        parameters = List.copyOf(parameters);
        preparedValues = List.copyOf(preparedValues);
    }
}
