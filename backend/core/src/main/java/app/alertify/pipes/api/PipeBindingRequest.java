package app.alertify.pipes.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PipeBindingRequest(
    @NotBlank @Size(max = 255) String targetParameterKey,
    @NotBlank @Size(max = 255) String sourceStepKey,
    @NotBlank @Size(max = 255) String sourceOutputKey
) {
}
