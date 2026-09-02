package app.alertify.alerts.api;

import java.time.Instant;

import app.alertify.alerts.template.annotation.AlertParameterSource;

/**
 * Public parameter binding. Secret references expose only metadata and never
 * the encrypted or plaintext secret value.
 */
public record AlertParameterValueResponse(
    Long id,
    long version,
    String parameterKey,
    AlertParameterSource source,
    String textValue,
    Long configurationId,
    String configurationName,
    Long secretId,
    String secretName,
    Instant createdAt,
    Instant updatedAt
) {
}
