package app.alertify.dashboard;

import java.time.Instant;

import app.alertify.alerts.api.AlertExecutionResponse;
import app.alertify.alerts.api.AlertResponse;

/**
 * One dashboard tile: an alert with the outcome of its most recent execution.
 * A null execution means the alert never ran yet; a non-null
 * {@code runningSince} means an execution is in progress on a worker.
 * {@code previousIssue} is the most recent WARN or ERROR execution before the
 * latest one inside the look-back window, or null when there was none.
 * {@code historyWindowDays} is the length of that window, which is configurable,
 * so clients label it without reading the system configuration themselves.
 * {@code persistentIssues} carries the latest WARN and ERROR of an alert whose
 * issues persist until seen; null when the option is off or none happened.
 */
public record DashboardCardResponse(AlertResponse alert, AlertExecutionResponse lastExecution, AlertExecutionResponse previousIssue, DashboardHistorySummaryResponse history, Instant runningSince, int historyWindowDays, DashboardIssueTimesResponse persistentIssues) {

    /** The tile as dashboard viewers receive it: worker network addresses stay on the administrative side. */
    public DashboardCardResponse forViewer() {
        return new DashboardCardResponse(alert, withoutWorkerAddress(lastExecution), withoutWorkerAddress(previousIssue), history, runningSince, historyWindowDays, persistentIssues);
    }

    private static AlertExecutionResponse withoutWorkerAddress(AlertExecutionResponse execution) {
        return execution == null ? null : execution.withoutWorkerAddress();
    }
}
