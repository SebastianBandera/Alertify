package app.alertify.secret.api;

import java.util.Set;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Write-only creation contract for a secret value. The value travels from the
 * client to encryption and has no corresponding field in {@link SecretResponse}.
 */
public record SecretCreateRequest(
    @NotBlank @Size(max = 200) String name,
    @Size(max = 2000) String description,
    @NotNull @Size(min = 1, max = 1048576) String value,
    Set<@Positive Long> tagIds,
    boolean writable
) {
}
