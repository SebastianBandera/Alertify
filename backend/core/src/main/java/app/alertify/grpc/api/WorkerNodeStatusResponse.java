package app.alertify.grpc.api;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import app.alertify.worker.contract.WorkerCapability;

public record WorkerNodeStatusResponse(
    String address,
    boolean available,
    String workerName,
    String workerInstanceId,
    Instant workerStartedAt,
    Set<WorkerCapability> capabilities,
    long totalExecuted,
    int runningCount,
    int waitingCount,
    int maxConcurrentAlerts,
    List<WorkerTaskStatusResponse> runningTasks,
    List<WorkerTaskStatusResponse> waitingTasks,
    String error
) {
}
