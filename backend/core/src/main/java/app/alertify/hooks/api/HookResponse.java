package app.alertify.hooks.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import app.alertify.configuration.api.TagResponse;
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
    Set<TagResponse> tags,
    List<HookTargetResponse> targets,
    Instant createdAt,
    Instant updatedAt
) {
}
