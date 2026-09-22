package app.alertify.api.error;

public class InvalidPipeImportException extends RuntimeException {
    public InvalidPipeImportException(String message) { super(message); }
    public InvalidPipeImportException(String message, Throwable cause) { super(message, cause); }
}
