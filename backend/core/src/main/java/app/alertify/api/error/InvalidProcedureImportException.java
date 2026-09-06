package app.alertify.api.error;

/**
 * A procedure CSV import could not be applied, for example because of a
 * malformed file or an unresolvable reference. Reported as HTTP 400.
 */
public class InvalidProcedureImportException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidProcedureImportException(String message) { super(message); }
    public InvalidProcedureImportException(String message, Throwable cause) { super(message, cause); }
}
