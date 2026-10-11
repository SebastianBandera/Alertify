package app.alertify.ai.api;

import java.util.List;
import java.util.Set;

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
    @NotNull @Size(max = 100) List<@NotNull @Valid AiAlertParameterValue> parameters,
    @NotNull @Size(max = 100) Set<@Positive Long> tagIds,
    Boolean persistentIssues,
    Boolean smartExecutionEnabled,
    @Positive Integer smartExecutionIntervalHours,
    SmartExecutionPolicy smartExecutionPolicy
) {

    public AlertUpdateRequest toRequest() {
        return new AlertUpdateRequest(version, name, description, cronExpression, enabled, allowConcurrentExecutions,
                parameters.stream().map(AiAlertParameterValue::toRequest).toList(), tagIds, persistentIssues,
                smartExecutionEnabled, smartExecutionIntervalHours, smartExecutionPolicy);
    }
}
