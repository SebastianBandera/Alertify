package app.alertify.api.error;

public class InvalidConfigurationValueException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidConfigurationValueException(String message) {
        super(message);
    }

    public InvalidConfigurationValueException(String message, Throwable cause) {
        super(message, cause);
    }
}
