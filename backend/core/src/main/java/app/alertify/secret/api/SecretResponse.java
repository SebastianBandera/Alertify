package app.alertify.secret.api;

import java.time.Instant;
import java.util.Set;

import app.alertify.configuration.api.TagResponse;
import app.alertify.jpa.entity.SecretValueType;

/**
 * Metadata-only secret response. It intentionally contains neither encrypted
 * bytes nor decrypted plaintext and only reports recoverability status.
 */
public record SecretResponse(
    Long id,
    long version,
    String name,
    String description,
    SecretValueType valueType,
    String binaryFileName,
    String binaryContentType,
    Long binarySize,
    Long binaryZipSize,
    Set<TagResponse> tags,
    boolean writable,
    SecretRecoveryStatus recoveryStatus,
    long valueRevision,
    Instant createdAt,
    Instant updatedAt
) {
}
