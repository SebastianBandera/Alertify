package app.alertify.ai.api;

import java.util.List;
import java.util.Set;

import org.springframework.ai.tool.annotation.ToolParam;

import app.alertify.procedures.api.ProcedureCreateRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record AiProcedureCreateRequest(
    @NotNull @Positive Long templateId,
    @NotBlank @Size(max = 200) String name,
    @Size(max = 2000) String description,
    @NotNull @Size(max = 255) String cronExpression,
    boolean enabled,
    Boolean allowConcurrentExecutions,
    @ToolParam(description = "Parameter bindings for the selected template. Each parameterKey must appear at most once. Supply all required parameters without defaults and every explicitly requested binding; omission may use a template default. Match each source to its required value or positive reference ID.") @NotNull @Size(max = 100) List<@NotNull @Valid AiProcedureParameterValue> parameters,
    @NotNull @Size(max = 100) Set<@Positive Long> tagIds
) {

    public ProcedureCreateRequest toRequest() {
        return new ProcedureCreateRequest(templateId, name, description, cronExpression, enabled,
                allowConcurrentExecutions, parameters.stream().map(AiProcedureParameterValue::toRequest).toList(), tagIds);
    }
}
