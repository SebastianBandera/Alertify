package app.alertify.alerts.template.annotation;

/**
 * Source selected by an alert instance for one parameter value. This describes
 * the configured value, not a restriction declared by the template.
 */
public enum AlertParameterSource {

    TEXT,
    CONFIGURATION,
    SECRET
}
