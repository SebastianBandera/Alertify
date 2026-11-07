package app.alertify.system.api;

import java.time.Instant;
import java.util.UUID;

import app.alertify.grpc.api.WorkerResourceUsageResponse;

/** A live snapshot of the responding orchestrator instance, independent of worker availability. */
public record BackendStatusResponse(
    String name,
    UUID instanceId,
    Instant startedAt,
    long uptimeMillis,
    Instant sampledAt,
    WorkerResourceUsageResponse resourceUsage
) {
}
