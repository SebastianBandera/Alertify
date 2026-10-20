package app.alertify.ai.api;

import app.alertify.alerts.api.AlertStateResponse;
import app.alertify.dashboard.DashboardCardResponse;

/** Complete current alert state as seen by the AI, including the dashboard card and persisted template state. */
public record AiAlertStateResponse(AlertStateResponse persistedState, DashboardCardResponse dashboardCard) {
}
