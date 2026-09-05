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

    /**
     * Suggested direct values. When binding is disabled this becomes the
     * exhaustive list of accepted values.
     */
    String[] options() default {};

    /**
     * Whether an alert instance may bind this parameter to a configuration or
     * secret. Direct text remains available; when false it must match an option.
     */
    boolean bindingAllowed() default true;

    /**
     * Direct value used when the alert instance does not provide one. An empty
     * value means that the parameter has no declared default.
     */
    String defaultValue() default "";

    /**
     * Whether direct text values should be edited as multiple lines in clients
     * that render the template metadata.
     */
    boolean multiline() default false;

    int order() default Integer.MAX_VALUE;

    boolean required() default true;
}
