package app.alertify.jpa.entity;

/**
 * Shape of the plaintext behind a secret. {@code STRING} is an opaque text
 * value; {@code DB_SECRET} is the canonical JSON of
 * {@link app.alertify.worker.contract.DatabaseCredentials}.
 */
public enum SecretValueType {
    STRING,
    DB_SECRET
}
