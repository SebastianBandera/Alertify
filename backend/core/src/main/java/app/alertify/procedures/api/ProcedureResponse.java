package app.alertify.procedures.api;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import app.alertify.configuration.api.TagResponse;

public record ProcedureResponse(
    Long id,
    long version,
    Long templateId,
    String templateKey,
    String templateNameKey,
    String name,
    String description,
    boolean enabled,
    boolean allowConcurrentExecutions,
    Set<TagResponse> tags,
    List<ProcedureParameterValueResponse> parameters,
    Instant createdAt,
    Instant updatedAt
) {
}
