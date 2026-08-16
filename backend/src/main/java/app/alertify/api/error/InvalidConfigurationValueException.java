package app.alertify.api.error;

public class InvalidConfigurationValueException extends RuntimeException {
    public InvalidConfigurationValueException(String message) { super(message); }
    public InvalidConfigurationValueException(String message, Throwable cause) { super(message, cause); }
}
