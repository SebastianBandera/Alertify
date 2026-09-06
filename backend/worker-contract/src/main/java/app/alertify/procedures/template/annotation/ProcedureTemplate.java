package app.alertify.procedures.template.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import app.alertify.worker.contract.WorkerCapability;

/** Marks a procedure evaluator implementation as a discoverable template. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ProcedureTemplate {

    String nameKey();

    String descriptionKey();

    ProcedureTemplateTag[] tags() default {};

    WorkerCapability capability() default WorkerCapability.STANDARD;

    String sourcePath();

    /** Whether successful output must be redacted from persistence and diagnostics. */
    boolean sensitiveResult() default false;
}
