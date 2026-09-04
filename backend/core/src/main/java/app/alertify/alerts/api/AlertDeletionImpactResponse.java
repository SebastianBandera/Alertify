package app.alertify.alerts.api;

/**
 * How much execution history deleting one alert would remove. The application
 * log is never affected.
 */
public record AlertDeletionImpactResponse(
    long alertId,
    String name,
    long executionCount
) {
}
