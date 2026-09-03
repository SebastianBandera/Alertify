package app.alertify.api.error;

public class InvalidAlertImportException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidAlertImportException(String message) {
        super(message);
    }

    public InvalidAlertImportException(String message, Throwable cause) {
        super(message, cause);
    }
}
