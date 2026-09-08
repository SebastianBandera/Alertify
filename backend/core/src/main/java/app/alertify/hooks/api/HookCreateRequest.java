package app.alertify.hooks.api;

import java.time.Duration;
import java.util.List;

import app.alertify.hooks.model.HookMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record HookCreateRequest(
    @NotBlank @Size(max = 255) String name,
    @Size(max = 4000) String description,
    @NotNull HookMode mode,
    @Positive Long tokenSecretId,
    @Positive Integer maxConcurrentInvocations,
    @Positive Integer rateLimitCount,
    Duration rateLimitWindow,
    @NotNull List<@Valid HookTargetRequest> targets
) {
}
