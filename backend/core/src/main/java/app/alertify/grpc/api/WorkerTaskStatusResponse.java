package app.alertify.grpc.api;

import java.time.Instant;

public record WorkerTaskStatusResponse(
    String executionId,
    long alertId,
    String alertName,
    Instant queuedAt,
    Instant workStartedAt,
    long elapsedMillis
) {
}
