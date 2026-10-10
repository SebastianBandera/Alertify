package app.alertify.ai;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.execution.DefaultToolCallResultConverter;
import org.springframework.ai.tool.execution.ToolCallResultConverter;
import org.springframework.core.annotation.AliasFor;

/** Declares a Spring AI tool together with Alertify-specific behavioral metadata. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Tool
public @interface AlertifyTool {

    @AliasFor(annotation = Tool.class, attribute = "name")
    String name() default "";

    @AliasFor(annotation = Tool.class, attribute = "description")
    String description() default "";

    @AliasFor(annotation = Tool.class, attribute = "returnDirect")
    boolean returnDirect() default false;

    @AliasFor(annotation = Tool.class, attribute = "resultConverter")
    Class<? extends ToolCallResultConverter> resultConverter() default DefaultToolCallResultConverter.class;

    /** Whether the tool avoids domain mutations, executions and external side effects. */
    boolean readOnly();
}
