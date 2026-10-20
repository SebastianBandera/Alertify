package app.alertify.ai;

import java.util.Objects;
import java.util.Set;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolCallResultConverter;

/** Spring AI callback paired with Alertify-specific behavioral metadata. */
public record AiToolDescriptor(
        ToolCallback callback,
        String name,
        String description,
        boolean returnDirect,
        Class<? extends ToolCallResultConverter> resultConverter,
        boolean readOnly,
        boolean destructiveHint,
        boolean idempotentHint,
        boolean openWorldHint,
        Set<AiRefreshTarget> refreshTargets) {

    public AiToolDescriptor {
        Objects.requireNonNull(callback, "callback");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(resultConverter, "resultConverter");
        refreshTargets = Set.copyOf(refreshTargets);
        if (!name.equals(callback.getToolDefinition().name()))
            throw new IllegalArgumentException("Descriptor name must match the Spring AI tool definition");

        if (!description.equals(callback.getToolDefinition().description()))
            throw new IllegalArgumentException("Descriptor description must match the Spring AI tool definition");

        if (returnDirect != callback.getToolMetadata().returnDirect())
            throw new IllegalArgumentException("Descriptor returnDirect must match the Spring AI tool metadata");
    }

    public String inputSchema() {
        return callback.getToolDefinition().inputSchema();
    }
}
