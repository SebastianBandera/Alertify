package app.alertify.api.error;

public class InvalidSecretValueException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidSecretValueException(String message) {
        super(message);
    }
}
