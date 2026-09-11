package app.alertify.system.api;

/** Aggregated system status: maintenance mode plus in-flight work across all workers. */
public record SystemStatusSummaryResponse(
    boolean maintenanceModeEnabled,
    int activeAlertExecutions,
    int waitingAlertExecutions,
    int activeProcedureExecutions,
    int waitingProcedureExecutions
) {
}
