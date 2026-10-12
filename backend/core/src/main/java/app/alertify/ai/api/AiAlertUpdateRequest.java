package app.alertify.ai.api;

import java.util.List;
import java.util.Set;

import org.springframework.ai.tool.annotation.ToolParam;

import app.alertify.alerts.api.AlertUpdateRequest;
import app.alertify.alerts.model.SmartExecutionPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record AiAlertUpdateRequest(
    @NotNull @PositiveOrZero Long version,
    @NotBlank @Size(max = 200) String name,
    @Size(max = 2000) String description,
    @NotNull @Size(max = 255) String cronExpression,
    boolean enabled,
    boolean allowConcurrentExecutions,
    @ToolParam(description = "Complete replacement list, not a patch. Start from the freshly read parameters, replace the requested entry by parameterKey exactly once and preserve all other entries and sources. Omitted entries may reset to template defaults or be removed; do not omit them to preserve them.") @NotNull @Size(max = 100) List<@NotNull @Valid AiAlertParameterValue> parameters,
    @NotNull @Size(max = 100) Set<@Positive Long> tagIds,
    @ToolParam(required = false, description = "null preserves issue persistence. Use true to enable or false to disable ONLY when the user explicitly requests that change. Do not use false as a neutral placeholder. The read response exposes persistentIssues and persistentIssuesSince.") Boolean persistentIssues,
    @ToolParam(required = false, description = "null preserves smart execution. Use true or false only when changing this option; it is independent of persistentIssues.") Boolean smartExecutionEnabled,
    @ToolParam(required = false, description = "null preserves the current smart interval when smart execution remains enabled. Supply a positive interval when enabling or changing smart execution; disabling smart execution clears its interval.") @PositiveOrZero Integer smartExecutionIntervalHours,
    @ToolParam(required = false, description = "null preserves the current smart policy when smart execution remains enabled. Disabling smart execution clears its policy. This option does not change persistentIssues.") SmartExecutionPolicy smartExecutionPolicy
) {

    public AlertUpdateRequest toRequest() {
        return new AlertUpdateRequest(version, name, description, cronExpression, enabled, allowConcurrentExecutions,
                parameters.stream().map(AiAlertParameterValue::toRequest).toList(), tagIds, persistentIssues,
                smartExecutionEnabled, smartExecutionIntervalHours, smartExecutionPolicy);
    }
}
