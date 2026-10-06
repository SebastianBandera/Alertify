package app.alertify.ai.api;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** Bounded pagination supplied by a model instead of an HTTP query string. */
public record AiPageRequest(int page, int size, String sort, Sort.Direction direction) {

    public AiPageRequest {
        if (page < 0)
            throw new IllegalArgumentException("Page must be zero or greater");

        if (size < 1 || size > 50)
            throw new IllegalArgumentException("Page size must be between 1 and 50");

        if (sort == null || sort.isBlank())
            throw new IllegalArgumentException("Sort field must not be blank");

        if (direction == null)
            throw new IllegalArgumentException("Sort direction is required");
    }

    public Pageable pageable() {
        return PageRequest.of(page, size, Sort.by(direction, sort));
    }
}
