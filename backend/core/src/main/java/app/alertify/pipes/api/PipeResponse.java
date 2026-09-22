package app.alertify.pipes.api;

import java.time.Instant;
import java.util.List;

public record PipeResponse(
    Long id,
    long version,
    String name,
    String description,
    boolean enabled,
    boolean allowConcurrentExecutions,
    List<PipeStepResponse> steps,
    Instant createdAt,
    Instant updatedAt
) {
}
