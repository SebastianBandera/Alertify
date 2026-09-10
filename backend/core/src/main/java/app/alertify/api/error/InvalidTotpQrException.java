package app.alertify.api.error;

/**
 * A pasted image could not be analyzed as a TOTP QR code, for example
 * because no QR code was found, it encodes an unsupported otpauth URI, or
 * its secret is not valid Base32. Reported as HTTP 400.
 */
public class InvalidTotpQrException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidTotpQrException(String message) { super(message); }
    public InvalidTotpQrException(String message, Throwable cause) { super(message, cause); }
}
