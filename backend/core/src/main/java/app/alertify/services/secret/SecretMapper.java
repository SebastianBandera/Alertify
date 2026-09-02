package app.alertify.services.secret;

import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.stereotype.Component;

import app.alertify.configuration.api.TagResponse;
import app.alertify.jpa.entity.ApplicationSecret;
import app.alertify.jpa.entity.Tag;
import app.alertify.secret.api.SecretRecoveryStatus;
import app.alertify.secret.api.SecretResponse;

/**
 * Maps secret entities to metadata-only API responses and checks whether each
 * encrypted value is recoverable with the current symmetric key.
 */
@Component
class SecretMapper {

    private final SecretEncryptionService encryptionService;

    SecretMapper(SecretEncryptionService encryptionService) {
        this.encryptionService = encryptionService;
    }

    SecretResponse toResponse(ApplicationSecret secret) {
        Set<TagResponse> tags = secret.getTags().stream()
                .sorted(java.util.Comparator.comparing(Tag::getName, String.CASE_INSENSITIVE_ORDER))
                .map(SecretMapper::toResponse)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        SecretRecoveryStatus recoveryStatus = encryptionService.isRecoverable(secret)
                ? SecretRecoveryStatus.RECOVERABLE
                : SecretRecoveryStatus.UNRECOVERABLE;

        return new SecretResponse(
                secret.getId(), secret.getVersion(), secret.getName(), secret.getDescription(), tags,
                recoveryStatus, secret.getValueRevision(), secret.getCreatedAt(), secret.getUpdatedAt()
        );
    }

    private static TagResponse toResponse(Tag tag) {
        return new TagResponse(
                tag.getId(), tag.getVersion(), tag.getScope(), tag.getName(), tag.getColor(),
                tag.getCreatedAt(), tag.getUpdatedAt()
        );
    }
}
