package app.alertify.procedures.api;

import java.time.Instant;

public record ProcedureTemplateOutputResponse(
    Long id,
    long version,
    String key,
    int order,
    Instant createdAt,
    Instant updatedAt
) {
}
