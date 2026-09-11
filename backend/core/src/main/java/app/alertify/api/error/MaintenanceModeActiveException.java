package app.alertify.api.error;

/**
 * A new alert or procedure execution was rejected because the system is
 * currently in maintenance mode. Reported as HTTP 409.
 */
public class MaintenanceModeActiveException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public MaintenanceModeActiveException(String message) { super(message); }
    public MaintenanceModeActiveException(String message, Throwable cause) { super(message, cause); }
}
