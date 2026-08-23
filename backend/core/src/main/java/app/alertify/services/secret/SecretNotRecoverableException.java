package app.alertify.services.secret;

/**
 * Signals that encrypted bytes cannot be safely recovered with the current
 * key or fail their integrity verification.
 */
public class SecretNotRecoverableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SecretNotRecoverableException(String secretName) {
        super("Secret '" + secretName + "' is not recoverable with the current symmetric key");
    }

    SecretNotRecoverableException(String secretName, Throwable cause) {
        super("Secret '" + secretName + "' is not recoverable with the current symmetric key", cause);
    }
}
