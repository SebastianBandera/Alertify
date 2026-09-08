package app.alertify.hooks.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import app.alertify.hooks.model.HookInvocationStatus;
import app.alertify.hooks.model.HookMode;

public record HookInvocationResponse(
    UUID invocationId,
    UUID hookPublicId,
    String hookName,
    HookMode mode,
    HookInvocationStatus status,
    Instant acceptedAt,
    Instant finishedAt,
    List<HookInvocationTargetResponse> targets
) {
}
