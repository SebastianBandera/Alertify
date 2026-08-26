package app.alertify.alerts.template.annotation;

import java.util.Objects;

/**
 * Produces the stable identifier shared by template discovery and persistence.
 */
public final class AlertTemplateIdentifier {

    private AlertTemplateIdentifier() {
    }

    public static String of(Class<?> templateClass) {
        Objects.requireNonNull(templateClass, "templateClass must not be null");
        if (!templateClass.isAnnotationPresent(AlertTemplate.class))
            throw new IllegalArgumentException(templateClass.getName() + " is not annotated with @AlertTemplate");
        return templateClass.getName();
    }
}
