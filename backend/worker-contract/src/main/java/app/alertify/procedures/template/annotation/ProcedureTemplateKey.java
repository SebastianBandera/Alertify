package app.alertify.procedures.template.annotation;

import java.util.Objects;

/** Produces the stable catalog key for a procedure template class. */
public final class ProcedureTemplateKey {

    private ProcedureTemplateKey() {
    }

    public static String of(Class<?> templateClass) {
        Objects.requireNonNull(templateClass, "templateClass must not be null");
        if (!templateClass.isAnnotationPresent(ProcedureTemplate.class))
            throw new IllegalArgumentException(templateClass.getName() + " is not annotated with @ProcedureTemplate");

        return templateClass.getName();
    }
}
