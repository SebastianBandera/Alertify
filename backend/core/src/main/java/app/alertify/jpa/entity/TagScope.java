package app.alertify.jpa.entity;

/**
 * Separates configuration, secret and alert tag namespaces at both application
 * and database levels.
 */
public enum TagScope {
    CONFIGURATION,
    SECRET,
    ALERT
}
