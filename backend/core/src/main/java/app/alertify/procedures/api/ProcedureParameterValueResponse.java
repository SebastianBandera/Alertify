package app.alertify.procedures.api;

import java.time.Instant;

import app.alertify.alerts.template.annotation.AlertParameterSource;

public record ProcedureParameterValueResponse(
    Long id,
    long version,
    String parameterKey,
    AlertParameterSource source,
    String textValue,
    Long configurationId,
    String configurationName,
    Long secretId,
    String secretName,
    Long procedureId,
    String procedureName,
    Instant createdAt,
    Instant updatedAt
) {
}
