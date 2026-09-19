package app.alertify.dashboard;

import java.time.Instant;

import app.alertify.alerts.execution.AlertExecutionStatus;

/**
 * Aggregate of the executions inside the dashboard look-back window (or at
 * least the latest one), enough to tell a steadily green alert apart from one
 * that recovered from a recent incident.
 *
 * @param worstStatus        worst status observed (ERROR > WARN > SUCCESS)
 * @param worstStatusLastAt  when that worst status was last observed
 * @param currentStatusSince start of the uninterrupted streak of the current status
 */
public record DashboardHistorySummaryResponse(AlertExecutionStatus worstStatus, Instant worstStatusLastAt, Instant currentStatusSince) {
}
