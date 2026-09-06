package app.alertify.procedures.execution;

/**
 * Lifecycle of a procedure execution row. Unlike an alert, a procedure has no
 * warning outcome: it either returns a result or fails.
 */
public enum ProcedureExecutionStatus {
    RUNNING,
    COMPLETED,
    ERROR
}
