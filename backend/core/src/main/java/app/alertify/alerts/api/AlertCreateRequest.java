package app.alertify.alerts.api;

import java.util.List;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record AlertCreateRequest(
    @NotNull @Positive Long templateId,
    @NotBlank @Size(max = 200) String name,
    @Size(max = 2000) String description,
    @NotBlank @Size(max = 255) String cronExpression,
    boolean enabled,
    boolean allowConcurrentExecutions,
    @NotNull @Size(max = 100) List<@Valid AlertParameterValueRequest> parameters,
    @NotNull @Size(max = 100) Set<@Positive Long> tagIds
) {
    public AlertCreateRequest(Long templateId, String name, String description, String cronExpression,
            boolean enabled, List<AlertParameterValueRequest> parameters, Set<Long> tagIds) {
        this(templateId, name, description, cronExpression, enabled, false, parameters, tagIds);
    }
}
