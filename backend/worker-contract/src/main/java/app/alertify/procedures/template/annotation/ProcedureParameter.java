package app.alertify.procedures.template.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import app.alertify.alerts.template.annotation.AlertParameterSource;

/** Describes one configurable constructor field of a procedure template. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ProcedureParameter {

    String labelKey();

    String descriptionKey();

    String[] options() default {};

    boolean bindingAllowed() default true;

    String defaultValue() default "";

    boolean multiline() default false;

    int order() default Integer.MAX_VALUE;

    boolean required() default true;

    AlertParameterSource[] allowedSources() default {
        AlertParameterSource.TEXT,
        AlertParameterSource.CONFIGURATION,
        AlertParameterSource.SECRET
    };
}
