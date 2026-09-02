package app.alertify.alerts.execution;

import java.util.List;

import app.alertify.worker.contract.WorkerCapability;

public record PreparedAlertExecution(
    long alertId,
    String alertName,
    String templateClassName,
    WorkerCapability requiredCapability,
    String sourceChecksum,
    String source,
    String state,
    List<ResolvedAlertParameter> parameters
) {
}
