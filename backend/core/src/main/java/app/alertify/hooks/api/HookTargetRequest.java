package app.alertify.hooks.api;

import java.time.Duration;
import java.util.Set;

import app.alertify.hooks.model.HookOutcome;
import app.alertify.hooks.model.HookTargetType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record HookTargetRequest(
    @NotNull HookTargetType type,
    @NotNull @Positive Long resourceId,
    @NotNull @Size(max = 3) Set<@NotNull HookOutcome> continueOn,
    Duration busyWaitTimeout
) {
}
