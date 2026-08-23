package app.alertify.configuration.api;

/**
 * Warning code interpreted by the frontend before a sensitive configuration
 * change; {@code SECRET_LOSS} indicates that changing key material may make
 * existing secrets unrecoverable.
 */
public enum ConfigurationWarning {
    SECRET_LOSS
}
