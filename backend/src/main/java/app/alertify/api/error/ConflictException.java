package app.alertify.api.error;

public class ConflictException extends RuntimeException {
    public ConflictException(String message) { super(message); }
}
