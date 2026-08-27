package app.alertify.alerts.template.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import app.alertify.worker.contract.WorkerCapability;

/**
 * Marks an alert evaluator implementation as a discoverable template.
 *
 * <p>The stable template key is deliberately not configurable: it is derived
 * from the annotated class package and name through
 * {@link AlertTemplateKey#of(Class)}. Persistence uses a separate numeric
 * primary key.</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface AlertTemplate {

    String nameKey();

    String descriptionKey();

    WorkerCapability capability() default WorkerCapability.STANDARD;
}
