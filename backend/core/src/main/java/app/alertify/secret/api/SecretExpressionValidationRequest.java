package app.alertify.secret.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Draft expression to validate before saving a secret. {@code secretId} and
 * {@code name} identify the secret being edited so self references and cycles
 * are detected; the evaluated value is never returned.
 */
public record SecretExpressionValidationRequest(
    Long secretId,
    @Size(max = 200) String name,
    @NotBlank @Size(max = 1_048_576) String expression
) {
}
