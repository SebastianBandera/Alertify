package app.alertify.alerts.api;

import java.time.Instant;
import java.util.List;

public record AlertResponse(
    Long id,
    long version,
    String templateId,
    String name,
    String description,
    String cronExpression,
    boolean enabled,
    List<AlertParameterValueResponse> parameters,
    Instant createdAt,
    Instant updatedAt
) {
}
