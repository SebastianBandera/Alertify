package app.alertify.procedures.api;

import java.time.Instant;
import java.util.UUID;

import app.alertify.procedures.execution.ProcedureExecutionStatus;
import app.alertify.procedures.execution.ProcedureExecutionTrigger;
import tools.jackson.databind.JsonNode;

public record ProcedureExecutionResponse(
    Long id,
    UUID executionId,
    Long procedureId,
    String procedureName,
    long procedureVersion,
    ProcedureExecutionStatus status,
    ProcedureExecutionTrigger trigger,
    UUID rootExecutionId,
    UUID parentAlertExecutionId,
    UUID parentProcedureExecutionId,
    int depth,
    Instant startedAt,
    Instant workStartedAt,
    Instant finishedAt,
    Long durationMillis,
    Long idleMillis,
    Long executionMillis,
    JsonNode result,
    boolean resultRedacted,
    String errorType,
    String errorMessage,
    String workerName,
    String workerIpAddress,
    Integer workerPort,
    UUID workerInstanceId,
    String triggeredBy
) {
}
