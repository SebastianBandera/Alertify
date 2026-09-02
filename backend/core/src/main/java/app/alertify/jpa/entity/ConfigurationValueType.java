package app.alertify.jpa.entity;

/**
 * Supported persisted configuration value types. {@code EXPRESSION} values
 * are stored as templates and resolved each time they are requested.
 */
public enum ConfigurationValueType {
    STRING,
    EXPRESSION,
    INTEGER,
    DECIMAL,
    BOOLEAN,
    DATE,
    TIME,
    DATE_TIME,
    JSON
}
