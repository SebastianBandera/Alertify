package app.alertify.alerts.template;

/**
 * Number of template classes and parameter definitions synchronized at startup.
 */
public record AlertTemplateRegistrationSummary(
    int templates, 
    int parameters
) {}
