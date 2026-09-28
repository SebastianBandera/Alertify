package app.alertify.alerts.model;

/** Controls when smart execution may run an alert whose configured interval is due. */
public enum SmartExecutionPolicy {

    ONCE_PER_INTERVAL,
    NORMAL,
    ON_ERROR,
    ON_WARN,
    ON_ERROR_OR_WARN
}
