package app.alertify.procedures.api;

import java.time.Instant;
import java.util.List;

import app.alertify.worker.contract.WorkerCapability;

public record ProcedureTemplateResponse(
    Long id,
    long version,
    String templateKey,
    String nameKey,
    String descriptionKey,
    WorkerCapability requiredCapability,
    boolean sensitiveResult,
    List<ProcedureTemplateTagResponse> tags,
    long procedureCount,
    List<ProcedureTemplateParameterResponse> parameters,
    Instant createdAt,
    Instant updatedAt
) {
}
