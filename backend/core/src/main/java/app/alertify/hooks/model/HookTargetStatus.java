package app.alertify.hooks.model;

public enum HookTargetStatus {
    PENDING,
    WAITING_ALERT,
    WAITING_PROCEDURE,
    RUNNING,
    SUCCESS,
    WARN,
    ERROR,
    SKIPPED_DISABLED,
    SKIPPED_MAINTENANCE,
    SKIPPED_SEQUENCE,
    ALERT_BUSY_TIMEOUT,
    PROCEDURE_BUSY_TIMEOUT
}
