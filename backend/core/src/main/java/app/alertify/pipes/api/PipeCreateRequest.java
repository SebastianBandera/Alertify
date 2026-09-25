package app.alertify.pipes.api;

import java.util.List;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record PipeCreateRequest(
    @NotBlank @Size(max = 255) String name,
    @Size(max = 4000) String description,
    boolean enabled,
    boolean allowConcurrentExecutions,
    @NotNull List<@Valid PipeStepRequest> steps,
    @NotNull @Size(max = 100) Set<@Positive Long> tagIds
) {
}
