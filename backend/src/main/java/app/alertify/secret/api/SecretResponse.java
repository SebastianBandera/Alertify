package app.alertify.secret.api;

import java.time.Instant;
import java.util.Set;

import app.alertify.configuration.api.TagResponse;

public record SecretResponse(
    Long id,
    long version,
    String name,
    String description,
    Set<TagResponse> tags,
    SecretRecoveryStatus recoveryStatus,
    long valueRevision,
    Instant createdAt,
    Instant updatedAt
) {
}
