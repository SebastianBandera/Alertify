package app.alertify.secret.api;

import java.util.Set;

import tools.jackson.databind.JsonNode;

import app.alertify.jpa.entity.SecretValueType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Write-only update contract that always replaces a secret with a newly
 * supplied value; the existing plaintext can never be requested by a client.
 * {@code newValue} follows the same shape rules as {@link SecretCreateRequest#value()}.
 */
public record SecretUpdateRequest(
    @NotNull @PositiveOrZero Long version,
    @NotBlank @Size(max = 200) String name,
    @Size(max = 2000) String description,
    @NotNull SecretValueType valueType,
    @NotNull JsonNode newValue,
    Set<@Positive Long> tagIds,
    boolean writable
) {
}
