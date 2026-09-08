package app.alertify.api.error;

public class InvalidHookRequestException extends RuntimeException {
    public InvalidHookRequestException(String message) { super(message); }
}
