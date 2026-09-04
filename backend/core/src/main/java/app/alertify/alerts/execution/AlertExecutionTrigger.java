package app.alertify.alerts.execution;

/**
 * What started an alert execution. Recorded in the application log so a manual
 * run and its operator can be told apart from the scheduled ones.
 */
public enum AlertExecutionTrigger {

    CRON,
    MANUAL
}
