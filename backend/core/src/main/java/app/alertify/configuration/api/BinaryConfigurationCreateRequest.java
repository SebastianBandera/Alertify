package app.alertify.configuration.api;

import java.util.Set;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record BinaryConfigurationCreateRequest(
    @NotBlank @Size(max = 200) String name,
    @Size(max = 2000) String description,
    Set<@Positive Long> tagIds,
    boolean writable
) { }
