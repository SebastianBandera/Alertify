package app.alertify.procedures.execution;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("procedure")
public record ProcedureExecutionProperties(int maxDepth) {
    public ProcedureExecutionProperties {
        if (maxDepth <= 0)
            throw new IllegalArgumentException("procedure.max-depth must be positive");
    }
}
