package app.alertify.alerts.api;

import java.time.Instant;
import java.util.UUID;

import app.alertify.alerts.execution.AlertExecutionStatus;
import app.alertify.alerts.execution.AlertExecutionTrigger;
import tools.jackson.databind.JsonNode;

public record AlertExecutionResponse(
    Long id,
    UUID executionId,
    Long alertId,
    String alertName,
    Long templateId,
    String templateNameKey,
    AlertExecutionStatus status,
    AlertExecutionTrigger trigger,
    String triggeredBy,
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
