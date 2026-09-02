package app.alertify.alerts.api;

import java.time.Instant;
import java.util.UUID;

import app.alertify.alerts.execution.AlertExecutionStatus;
import tools.jackson.databind.JsonNode;

public record AlertExecutionResponse(
    Long id,
    UUID executionId,
    Long alertId,
    String alertName,
    AlertExecutionStatus status,
    Instant startedAt,
    Instant workStartedAt,
    Instant finishedAt,
    long durationMillis,
    long idleMillis,
    long executionMillis,
    JsonNode statusMessage,
    String errorType,
    String errorMessage,
    String workerName,
    String workerIpAddress,
    Integer workerPort,
    UUID workerInstanceId
) {
}
