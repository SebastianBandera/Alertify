package app.alertify.alerts.template.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Describes one configurable field of an {@link AlertTemplate} class. The
 * parameter key is derived from the annotated field name.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface AlertParameter {

    String labelKey();

    String descriptionKey();

    AlertParameterSource[] sources();

    String[] options() default {};

    int order() default Integer.MAX_VALUE;

    boolean required() default true;
}
