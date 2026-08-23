package app.alertify.jpa.entity;

/**
 * Separates configuration and secret tag namespaces at both application and
 * database levels.
 */
public enum TagScope {
    CONFIGURATION,
    SECRET
}
