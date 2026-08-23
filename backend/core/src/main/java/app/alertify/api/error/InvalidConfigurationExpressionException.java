package app.alertify.api.error;

public class InvalidConfigurationExpressionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidConfigurationExpressionException(String message) {
        super(message);
    }
}
