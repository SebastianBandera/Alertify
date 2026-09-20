package app.alertify.api.error;

/**
 * A hook CSV import could not be read at all, for example because of a
 * malformed file or an unexpected header. Row-level problems are reported in
 * the import result instead. Reported as HTTP 400.
 */
public class InvalidHookImportException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidHookImportException(String message) { super(message); }
    public InvalidHookImportException(String message, Throwable cause) { super(message, cause); }
}
