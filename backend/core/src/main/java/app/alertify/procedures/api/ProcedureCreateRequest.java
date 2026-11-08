package app.alertify.procedures.api;

import java.util.List;
import java.util.Set;

import app.alertify.alerts.model.ExecutionMode;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ProcedureCreateRequest(
    @NotNull @Positive Long templateId,
    @NotBlank @Size(max = 200) String name,
    @Size(max = 2000) String description,
    @NotNull @Size(max = 255) String cronExpression,
    boolean enabled,
    Boolean allowConcurrentExecutions,
    @NotNull @Size(max = 100) List<@Valid ProcedureParameterValueRequest> parameters,
    @NotNull @Size(max = 100) Set<@Positive Long> tagIds,
    ExecutionMode executionMode
) {
    public ProcedureCreateRequest(Long templateId, String name, String description, String cronExpression, boolean enabled, Boolean allowConcurrentExecutions, List<ProcedureParameterValueRequest> parameters, Set<Long> tagIds) {
        this(templateId, name, description, cronExpression, enabled, allowConcurrentExecutions, parameters, tagIds, null);
    }
}
