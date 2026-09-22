package app.alertify.api.error;

public class InvalidPipeRequestException extends RuntimeException {
    public InvalidPipeRequestException(String message) { super(message); }
    public InvalidPipeRequestException(String message, Throwable cause) { super(message, cause); }
}
