package app.alertify.configuration.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record TagUpdateRequest(
    @NotNull @PositiveOrZero Long version,
    @NotBlank @Size(max = 100) String name,
    @NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String color
) {
}
