package app.alertify.alerts.api;

import java.time.Instant;
import java.util.List;

import app.alertify.worker.contract.WorkerCapability;

public record AlertTemplateResponse(
    Long id,
    long version,
    String templateKey,
    String nameKey,
    String descriptionKey,
    WorkerCapability requiredCapability,
    List<AlertTemplateTagResponse> tags,
    long alertCount,
    List<AlertTemplateParameterResponse> parameters,
    Instant createdAt,
    Instant updatedAt
) {
}
