package app.alertify.alerts.api;

/**
 * Explicit response for an alert's potentially large runtime state.
 */
public record AlertStateResponse(
    Long alertId,
    String state
) {
}
