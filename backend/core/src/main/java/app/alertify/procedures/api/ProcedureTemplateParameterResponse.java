package app.alertify.procedures.api;

import java.time.Instant;
import java.util.List;

import app.alertify.alerts.template.annotation.AlertParameterSource;

public record ProcedureTemplateParameterResponse(
    Long id,
    long version,
    String key,
    String labelKey,
    String descriptionKey,
    String javaType,
    List<String> options,
    boolean bindingAllowed,
    String defaultValue,
    boolean multiline,
    int order,
    boolean required,
    List<AlertParameterSource> allowedSources,
    Instant createdAt,
    Instant updatedAt
) {
}
