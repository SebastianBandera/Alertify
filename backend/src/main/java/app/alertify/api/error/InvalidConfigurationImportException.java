package app.alertify.api.error;

public class InvalidConfigurationImportException extends RuntimeException {
    public InvalidConfigurationImportException(String message) { super(message); }
    public InvalidConfigurationImportException(String message, Throwable cause) { super(message, cause); }
}
