package app.alertify.dashboard;

import java.time.Instant;

import app.alertify.alerts.api.AlertExecutionResponse;
import app.alertify.alerts.api.AlertResponse;

/**
 * One dashboard tile: an alert with the outcome of its most recent execution.
 * A null execution means the alert never ran yet; a non-null
 * {@code runningSince} means an execution is in progress on a worker.
 */
public record DashboardCardResponse(AlertResponse alert, AlertExecutionResponse lastExecution, DashboardHistorySummaryResponse history, Instant runningSince) {
}
