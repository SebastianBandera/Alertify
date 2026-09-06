package app.alertify.procedures;

/** Raised before dispatch when a procedure chain would exceed its maximum depth. */
public final class ProcedureDepthExceededException extends ProcedureExecutionException {

    public ProcedureDepthExceededException(String message) {
        super(message);
    }
}
