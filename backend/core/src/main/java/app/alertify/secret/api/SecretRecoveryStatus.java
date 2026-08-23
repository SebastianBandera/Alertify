package app.alertify.secret.api;

/**
 * Indicates whether a stored secret can be decrypted and verified with the
 * key currently derived by the backend.
 */
public enum SecretRecoveryStatus {
    RECOVERABLE,
    UNRECOVERABLE
}
