package app.alertify.alerts.api;

import java.time.Instant;

import app.alertify.alerts.execution.AlertExecutionStatus;
import tools.jackson.databind.JsonNode;

public record AlertExecutionResponse(
    Long id,
    Long alertId,
    AlertExecutionStatus status,
    Instant startedAt,
    Instant finishedAt,
    long durationMillis,
    JsonNode statusMessage,
    String errorType,
    String errorMessage
) {
}
