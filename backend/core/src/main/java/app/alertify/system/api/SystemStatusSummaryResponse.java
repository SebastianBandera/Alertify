package app.alertify.system.api;

import java.util.List;

/** Aggregated system status: maintenance mode plus in-flight work across all workers. */
public record SystemStatusSummaryResponse(
    boolean maintenanceModeEnabled,
    boolean cronQuietHoursActive,
    int activeAlertExecutions,
    int waitingAlertExecutions,
    int activeProcedureExecutions,
    int waitingProcedureExecutions,
    List<WorkerQueueStatusResponse> saturatedWorkers
) {
}
