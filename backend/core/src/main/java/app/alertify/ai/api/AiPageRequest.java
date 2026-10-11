package app.alertify.ai.api;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Bounded pagination supplied by a model instead of an HTTP query string. */
public record AiPageRequest(@Min(0) @Schema(minimum = "0", description = "Zero-based page number") int page,
        @Min(1) @Max(MAX_SIZE) @Schema(minimum = "1", maximum = "50", description = "Requested result count; Alertify caps values above 50") int size, @NotBlank String sort,
        @NotNull Sort.Direction direction) {

    public static final int MAX_SIZE = 50;

    public AiPageRequest {
        if (page < 0)
            throw new IllegalArgumentException("Page must be zero or greater");

        if (size < 1)
            throw new IllegalArgumentException("Page size must be one or greater");

        size = Math.min(size, MAX_SIZE);

        if (sort == null || sort.isBlank())
            throw new IllegalArgumentException("Sort field must not be blank");

        if (direction == null)
            throw new IllegalArgumentException("Sort direction is required");
    }

    public Pageable pageable() {
        return PageRequest.of(page, size, Sort.by(direction, sort));
    }
}
