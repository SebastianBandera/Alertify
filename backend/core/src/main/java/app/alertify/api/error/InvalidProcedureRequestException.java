package app.alertify.api.error;

/**
 * A procedure request is well formed but semantically invalid, for example an
 * unknown parameter or a value source the template does not allow. Reported as
 * HTTP 400.
 */
public class InvalidProcedureRequestException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidProcedureRequestException(String message) {
        super(message);
    }

    public InvalidProcedureRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
