package app.alertify.pipes.api;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import app.alertify.configuration.api.TagResponse;
public record PipeResponse(
    Long id,
    long version,
    String name,
    String description,
    boolean enabled,
    boolean allowConcurrentExecutions,
    Set<TagResponse> tags,
    List<PipeStepResponse> steps,
    Instant createdAt,
    Instant updatedAt
) {
}
