package app.alertify.secret.api;

/**
 * Outcome of probing {@code DB_SECRET} credentials from a worker. {@code failureReason}
 * uses the reason codes of {@code DatabaseConnectionAlertTemplate} (timeout, auth_failed,
 * driver_missing, ...) plus {@code execution_error} when the probe itself could not run.
 */
public record DatabaseSecretTestResponse(
    boolean connected,
    String failureReason,
    String failureMessage,
    String sqlState,
    String productName,
    String productVersion,
    String driverName,
    Long connectMs,
    Long totalLatencyMs,
    String workerName
) {
}
