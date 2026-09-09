package app.alertify.procedures;

/** Raised when a non-concurrent Procedure already has an active execution. */
public final class ProcedureBusyException extends ProcedureExecutionException {
    public ProcedureBusyException(String message) { super(message); }
}
