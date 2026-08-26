package app.alertify.alerts.api;

import java.time.Instant;
import java.util.List;

import app.alertify.worker.contract.WorkerCapability;

public record AlertTemplateResponse(
    String id,
    long version,
    String nameKey,
    String descriptionKey,
    WorkerCapability requiredCapability,
    List<AlertTemplateParameterResponse> parameters,
    Instant createdAt,
    Instant updatedAt
) {
}
