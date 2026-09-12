package app.alertify.procedures.execution;

/**
 * What started a procedure execution: cron, a user running it from the UI, a
 * hook, or a parameter handle invoked by a running alert or procedure.
 */
public enum ProcedureExecutionTrigger {
    CRON,
    MANUAL,
    ALERT,
    PROCEDURE,
    HOOK
}
