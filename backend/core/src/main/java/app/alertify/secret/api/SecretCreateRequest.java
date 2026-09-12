package app.alertify.secret.api;

import java.util.Set;

import tools.jackson.databind.JsonNode;

import app.alertify.jpa.entity.SecretValueType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Write-only creation contract for a secret value. The value travels from the
 * client to encryption and has no corresponding field in {@link SecretResponse}.
 * Its JSON shape depends on {@code valueType}: a string for {@code STRING}, an
 * object with the connection fields for {@code DB_SECRET}.
 */
public record SecretCreateRequest(
    @NotBlank @Size(max = 200) String name,
    @Size(max = 2000) String description,
    @NotNull SecretValueType valueType,
    @NotNull JsonNode value,
    Set<@Positive Long> tagIds,
    boolean writable
) {
}
