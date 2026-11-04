package app.alertify.ai.api;

import java.util.List;
import java.util.Set;

import org.springframework.ai.tool.annotation.ToolParam;

import app.alertify.alerts.api.AlertCreateRequest;
import app.alertify.alerts.model.ExecutionMode;
import app.alertify.alerts.model.SmartExecutionPolicy;
import app.alertify.alerts.model.SmartExecutionIntervalSource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record AiAlertCreateRequest(
    @NotNull @Positive Long templateId,
    @NotBlank @Size(max = 200) String name,
    @Size(max = 2000) String description,
    @NotNull @Size(max = 255) String cronExpression,
    boolean enabled,
    boolean allowConcurrentExecutions,
    @ToolParam(description = "Parameter bindings for the selected template. Each parameterKey must appear at most once. Supply all required parameters without defaults and every explicitly requested binding; omission may use a template default. Match each source to its required value or positive reference ID.") @NotNull @Size(max = 100) List<@NotNull @Valid AiAlertParameterValue> parameters,
    @NotNull @Size(max = 100) Set<@Positive Long> tagIds,
    Boolean persistentIssues,
    Boolean smartExecutionEnabled,
    @PositiveOrZero Integer smartExecutionIntervalHours,
    SmartExecutionPolicy smartExecutionPolicy,
    @ToolParam(required = false, description = "Defaults to FIXED; CONFIGURATION reads hours from the INTEGER Config identified by smartExecutionIntervalConfigurationId, clamped to 1..2147483647. A missing or incompatible Config records one SMART ERROR per incident.") SmartExecutionIntervalSource smartExecutionIntervalSource,
    @ToolParam(required = false, description = "Positive INTEGER configuration ID for a CONFIGURATION interval. Do not change the Config writable flag.") @Positive Long smartExecutionIntervalConfigurationId,
    @ToolParam(required = false, description = "Informational intended presentation context. Defaults to STANDALONE when omitted. PIPE groups the alert under Pipes on the dashboard; it does not change cron, smart execution or manual execution.") ExecutionMode executionMode
) {
    public AiAlertCreateRequest(Long templateId, String name, String description, String cronExpression, boolean enabled, boolean allowConcurrentExecutions, List<AiAlertParameterValue> parameters, Set<Long> tagIds, Boolean persistentIssues, Boolean smartExecutionEnabled, Integer smartExecutionIntervalHours, SmartExecutionPolicy smartExecutionPolicy, SmartExecutionIntervalSource smartExecutionIntervalSource, Long smartExecutionIntervalConfigurationId) {
        this(templateId, name, description, cronExpression, enabled, allowConcurrentExecutions, parameters, tagIds, persistentIssues, smartExecutionEnabled, smartExecutionIntervalHours, smartExecutionPolicy, smartExecutionIntervalSource, smartExecutionIntervalConfigurationId, null);
    }

    public AiAlertCreateRequest(Long templateId, String name, String description, String cronExpression, boolean enabled, boolean allowConcurrentExecutions, List<AiAlertParameterValue> parameters, Set<Long> tagIds, Boolean persistentIssues, Boolean smartExecutionEnabled, Integer smartExecutionIntervalHours, SmartExecutionPolicy smartExecutionPolicy) {
        this(templateId, name, description, cronExpression, enabled, allowConcurrentExecutions, parameters, tagIds, persistentIssues, smartExecutionEnabled, smartExecutionIntervalHours, smartExecutionPolicy, null, null);
    }


    public AlertCreateRequest toRequest() {
        return new AlertCreateRequest(templateId, name, description, cronExpression, enabled, allowConcurrentExecutions,
                parameters.stream().map(AiAlertParameterValue::toRequest).toList(), tagIds, persistentIssues,
                smartExecutionEnabled, smartExecutionIntervalHours, smartExecutionPolicy, smartExecutionIntervalSource, smartExecutionIntervalConfigurationId, executionMode);
    }
}
