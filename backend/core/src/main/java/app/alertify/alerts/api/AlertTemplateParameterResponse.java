package app.alertify.alerts.api;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import app.alertify.alerts.template.annotation.AlertParameterSource;

public record AlertTemplateParameterResponse(
    Long id,
    long version,
    String key,
    String labelKey,
    String descriptionKey,
    String javaType,
    Set<AlertParameterSource> allowedSources,
    List<String> options,
    int order,
    boolean required,
    Instant createdAt,
    Instant updatedAt
) {
}
