package app.alertify.ai.api;

import java.util.List;
import java.util.Set;

import app.alertify.pipes.api.PipeCreateRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record AiPipeCreateRequest(
    @NotBlank @Size(max = 255) String name,
    @Size(max = 4000) String description,
    boolean enabled,
    boolean allowConcurrentExecutions,
    @Schema(description = "Optional positive ISO-8601 duration for the FINALLY phase, for example PT30S; use null or an empty string for the default") String finallyTimeout,
    @NotNull List<@NotNull @Valid AiPipeStep> steps,
    @NotNull @Size(max = 100) Set<@Positive Long> tagIds
) {

    public PipeCreateRequest toRequest() {
        return new PipeCreateRequest(name, description, enabled, allowConcurrentExecutions,
                AiRequestValues.optionalDuration(finallyTimeout, "finallyTimeout"),
                steps.stream().map(AiPipeStep::toRequest).toList(), tagIds);
    }
}
