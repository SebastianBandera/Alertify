package app.alertify.hooks.api;

import java.time.Duration;
import java.util.Set;

import app.alertify.hooks.model.HookOutcome;
import app.alertify.hooks.model.HookTargetType;

public record HookTargetResponse(
    Long id,
    HookTargetType type,
    Long resourceId,
    String resourceName,
    boolean enabled,
    int position,
    Set<HookOutcome> continueOn,
    Duration busyWaitTimeout
) {
}
