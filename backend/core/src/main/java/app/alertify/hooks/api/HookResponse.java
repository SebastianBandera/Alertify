package app.alertify.hooks.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import app.alertify.hooks.model.HookMode;

public record HookResponse(
    Long id,
    long version,
    UUID publicId,
    String name,
    String description,
    boolean enabled,
    HookMode mode,
    Long tokenSecretId,
    String tokenSecretName,
    Integer maxConcurrentInvocations,
    Integer rateLimitCount,
    Duration rateLimitWindow,
    List<HookTargetResponse> targets,
    Instant createdAt,
    Instant updatedAt
) {
}
