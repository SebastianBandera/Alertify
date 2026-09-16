package app.alertify.jpa.entity;

/**
 * Shape of the plaintext behind a secret. {@code STRING} is an opaque text
 * value; {@code DB_SECRET} is the canonical JSON of
 * {@link app.alertify.worker.contract.DatabaseCredentials}; {@code GIT_SECRET}
 * is the canonical JSON of {@link app.alertify.worker.contract.GitCredentials};
 * {@code EXPRESSION} is a template resolved on every access that may
 * reference other secrets, configurations, environment variables and
 * utilities.
 */
public enum SecretValueType {
    STRING,
    DB_SECRET,
    GIT_SECRET,
    EXPRESSION,
    BINARY
}
