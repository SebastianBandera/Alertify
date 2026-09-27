package app.alertify.alerts.model;

/** Controls when an overdue smart execution may retry an alert. */
public enum SmartExecutionPolicy {

    NORMAL,
    ON_ERROR,
    ON_WARN,
    ON_ERROR_OR_WARN
}
