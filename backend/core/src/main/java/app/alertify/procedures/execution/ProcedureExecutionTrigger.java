package app.alertify.procedures.execution;

/**
 * What started a procedure execution: a user running it from the UI, or a
 * parameter handle invoked by a running alert or procedure.
 */
public enum ProcedureExecutionTrigger {
    MANUAL,
    ALERT,
    PROCEDURE
}
