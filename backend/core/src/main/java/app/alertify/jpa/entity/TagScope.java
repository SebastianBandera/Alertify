package app.alertify.jpa.entity;

/**
 * Separates the configuration, secret, alert, procedure, hook and pipe tag namespaces at
 * both application and database levels.
 */
public enum TagScope {
    CONFIGURATION,
    SECRET,
    ALERT,
    PROCEDURE,
    HOOK,
    PIPE
}
