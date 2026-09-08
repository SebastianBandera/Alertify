package app.alertify.hooks.api;

import java.util.UUID;

import app.alertify.hooks.model.HookTargetStatus;
import app.alertify.hooks.model.HookTargetType;

public record HookInvocationTargetResponse(
    HookTargetType type,
    String resourceName,
    int position,
    HookTargetStatus status,
    UUID executionId
) {
}
