package app.alertify.api.error;

public class InvalidAlertRequestException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidAlertRequestException(String message) {
        super(message);
    }

    public InvalidAlertRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
