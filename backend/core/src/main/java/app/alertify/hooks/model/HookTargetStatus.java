package app.alertify.hooks.model;

public enum HookTargetStatus {
    PENDING,
    WAITING_ALERT,
    RUNNING,
    SUCCESS,
    WARN,
    ERROR,
    SKIPPED_DISABLED,
    SKIPPED_SEQUENCE,
    ALERT_BUSY_TIMEOUT
}
