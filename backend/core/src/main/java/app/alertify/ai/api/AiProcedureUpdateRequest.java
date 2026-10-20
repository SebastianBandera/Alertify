package app.alertify.ai.api;

import java.util.List;
import java.util.Set;

import org.springframework.ai.tool.annotation.ToolParam;

import app.alertify.procedures.api.ProcedureUpdateRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record AiProcedureUpdateRequest(
    @NotNull @PositiveOrZero Long version,
    @NotBlank @Size(max = 200) String name,
    @Size(max = 2000) String description,
    @NotNull @Size(max = 255) String cronExpression,
    boolean enabled,
    boolean allowConcurrentExecutions,
    @ToolParam(description = "Complete replacement list, not a patch. Start from the freshly read parameters, replace the requested entry by parameterKey exactly once and preserve all other entries and sources. Omitted entries may reset to template defaults or be removed; do not omit them to preserve them.") @NotNull @Size(max = 100) List<@NotNull @Valid AiProcedureParameterValue> parameters,
    @NotNull @Size(max = 100) Set<@Positive Long> tagIds
) {

    public ProcedureUpdateRequest toRequest() {
        return new ProcedureUpdateRequest(version, name, description, cronExpression, enabled,
                allowConcurrentExecutions, parameters.stream().map(AiProcedureParameterValue::toRequest).toList(), tagIds);
    }
}
