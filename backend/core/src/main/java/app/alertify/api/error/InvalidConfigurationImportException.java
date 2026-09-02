package app.alertify.api.error;

public class InvalidConfigurationImportException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidConfigurationImportException(String message) {
        super(message);
    }

    public InvalidConfigurationImportException(String message, Throwable cause) {
        super(message, cause);
    }
}
