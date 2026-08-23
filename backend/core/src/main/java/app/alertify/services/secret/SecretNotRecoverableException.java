package app.alertify.services.secret;

public class SecretNotRecoverableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SecretNotRecoverableException(String secretName) {
        super("Secret '" + secretName + "' is not recoverable with the current symmetric key");
    }

    SecretNotRecoverableException(String secretName, Throwable cause) {
        super("Secret '" + secretName + "' is not recoverable with the current symmetric key", cause);
    }
}
