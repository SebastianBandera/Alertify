package app.alertify.alerts.execution;

/**
 * Final outcome of one alert execution. ERROR is reserved for exceptions.
 */
public enum AlertExecutionStatus {

    SUCCESS,
    WARN,
    ERROR
}
