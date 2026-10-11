package app.alertify.ai.api;

import java.util.Set;

import app.alertify.hooks.api.HookTargetRequest;
import app.alertify.hooks.model.HookOutcome;
import app.alertify.hooks.model.HookTargetType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record AiHookTarget(
    @NotNull HookTargetType type,
    @NotNull @Positive Long resourceId,
    @NotNull @Size(max = 3) Set<@NotNull HookOutcome> continueOn,
    @Schema(description = "Optional positive ISO-8601 duration, for example PT30S; use null or an empty string for the default") String busyWaitTimeout
) {

    public HookTargetRequest toRequest() {
        return new HookTargetRequest(type, resourceId, continueOn,
                AiRequestValues.optionalDuration(busyWaitTimeout, "busyWaitTimeout"));
    }
}
