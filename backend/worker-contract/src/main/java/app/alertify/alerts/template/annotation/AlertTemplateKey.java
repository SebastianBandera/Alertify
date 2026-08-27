package app.alertify.alerts.template.annotation;

import java.util.Objects;

/**
 * Produces the stable alternate key used to match a template class with its
 * persistent catalog entry.
 */
public final class AlertTemplateKey {

    private AlertTemplateKey() {
    }

    public static String of(Class<?> templateClass) {
        Objects.requireNonNull(templateClass, "templateClass must not be null");
        if (!templateClass.isAnnotationPresent(AlertTemplate.class))
            throw new IllegalArgumentException(templateClass.getName() + " is not annotated with @AlertTemplate");
        return templateClass.getName();
    }
}
