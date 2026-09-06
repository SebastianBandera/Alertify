package app.alertify.procedures;

/** Raised when a nested invocation targets a currently disabled procedure. */
public final class ProcedureDisabledException extends ProcedureExecutionException {

    public ProcedureDisabledException(String message) {
        super(message);
    }
}
