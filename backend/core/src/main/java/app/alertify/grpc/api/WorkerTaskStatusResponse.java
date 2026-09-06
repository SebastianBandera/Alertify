package app.alertify.grpc.api;

import java.time.Instant;

public record WorkerTaskStatusResponse(
    String executionId,
    String kind,
    long resourceId,
    String resourceName,
    String parentExecutionId,
    int depth,
    Instant queuedAt,
    Instant workStartedAt,
    long elapsedMillis
) {
}
