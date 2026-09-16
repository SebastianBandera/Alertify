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
     * Whether configuration and secret bindings must allow write-back. This is
     * intended for mutable template state such as binary snapshots; direct text
     * and procedure bindings are invalid when this flag is enabled.
     */
    boolean writableBindingRequired() default false;

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

    /**
     * Sources an alert instance may use to provide this parameter's value.
     * Defaults to the historical behaviour of offering text, configuration and
     * secret bindings; {@code PROCEDURE} is reserved for fields of type
     * {@code Procedure} and is enforced separately at registration time.
     */
    AlertParameterSource[] allowedSources() default {
        AlertParameterSource.TEXT,
        AlertParameterSource.CONFIGURATION,
        AlertParameterSource.SECRET
    };

    /**
     * Names of {@code ConfigurationValueType} constants this parameter may bind
     * to when the source is {@code CONFIGURATION}. An empty array means no
     * additional restriction beyond the physical java type/value type coherence
     * already enforced for special value types such as {@code BINARY}.
     */
    String[] allowedConfigurationValueTypes() default {};

    /**
     * Names of {@code SecretValueType} constants this parameter may bind to
     * when the source is {@code SECRET}. An empty array means no additional
     * restriction beyond the physical java type/value type coherence already
     * enforced for special value types such as {@code BINARY} or
     * {@code DB_SECRET}.
     */
    String[] allowedSecretValueTypes() default {};
}
