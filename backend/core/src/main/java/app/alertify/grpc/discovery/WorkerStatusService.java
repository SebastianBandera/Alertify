package app.alertify.grpc.discovery;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Service;

import app.alertify.grpc.AlertWorkerClient;
import app.alertify.grpc.WorkerGrpcProperties;
import app.alertify.grpc.api.WorkerNodeStatusResponse;
import app.alertify.grpc.api.WorkerTaskStatusResponse;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.worker.contract.WorkerCapability;
import app.alertify.worker.grpc.WorkerStatusResponse;
import app.alertify.worker.grpc.WorkerTask;

@Service
public class WorkerStatusService implements AutoCloseable {

    private final WorkerAvailabilityService availabilityService;
    private final AlertWorkerClient client;
    private final WorkerGrpcProperties properties;
    private final ApplicationEventLogger eventLogger;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentMap<WorkerEndpoint, AtomicInteger> reservations = new ConcurrentHashMap<>();

    public WorkerStatusService(WorkerAvailabilityService availabilityService, AlertWorkerClient client, WorkerGrpcProperties properties, ApplicationEventLogger eventLogger) {
        this.availabilityService = availabilityService;
        this.client = client;
        this.properties = properties;
        this.eventLogger = eventLogger;
    }

    public synchronized WorkerReservation reserve(WorkerCapability capability) {
        List<SelectedWorker> workers = statusFor(availabilityService.availableWorkersWith(capability)).stream()
                .filter(WorkerStatusResult::available)
                .map(result -> new SelectedWorker(result.endpoint(), result.status()))
                .sorted(Comparator.comparingInt(this::effectiveLoad).thenComparing(worker -> worker.endpoint().toString()))
                .toList();
        if (workers.isEmpty())
            throw new IllegalStateException("No available worker supports capability " + capability);
        SelectedWorker selected = workers.getFirst();
        reservations.computeIfAbsent(selected.endpoint(), key -> new AtomicInteger()).incrementAndGet();
        return new WorkerReservation(selected, () -> release(selected.endpoint()));
    }

    public List<WorkerNodeStatusResponse> status() {
        List<WorkerStatusResult> results = statusFor(availabilityService.availableWorkers());
        eventLogger.success("WORKER_STATUS_VIEWED", java.util.Map.of("workerCount", results.size()));
        return results.stream()
                .map(WorkerStatusService::toResponse)
                .sorted(Comparator.comparing(WorkerNodeStatusResponse::address))
                .toList();
    }

    private List<WorkerStatusResult> statusFor(Set<AvailableWorker> workers) {
        List<Future<WorkerStatusResult>> futures = new ArrayList<>();
        for (AvailableWorker worker : workers)
            futures.add(executor.submit(() -> inspect(worker)));
        List<WorkerStatusResult> results = new ArrayList<>();
        for (Future<WorkerStatusResult> future : futures) {
            try {
                results.add(future.get());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Worker status inspection was interrupted", exception);
            } catch (ExecutionException exception) {
                throw new IllegalStateException("Worker status inspection failed", exception.getCause());
            }
        }
        return results;
    }

    private WorkerStatusResult inspect(AvailableWorker worker) {
        WorkerEndpoint endpoint = new WorkerEndpoint(worker.ipAddress(), worker.port());
        try {
            WorkerStatusResponse status = client.status(endpoint, properties.discovery().healthTimeout());
            return new WorkerStatusResult(endpoint, worker.capabilities(), status, null);
        } catch (RuntimeException exception) {
            return new WorkerStatusResult(
                    endpoint, worker.capabilities(), null,
                    exception.getMessage() == null ? exception.getClass().getName() : exception.getMessage()
            );
        }
    }

    private int effectiveLoad(SelectedWorker worker) {
        AtomicInteger reserved = reservations.get(worker.endpoint());
        return worker.currentLoad() + (reserved == null ? 0 : reserved.get());
    }

    private void release(WorkerEndpoint endpoint) {
        reservations.computeIfPresent(endpoint, (key, count) ->
            count.decrementAndGet() <= 0 ? null : count
        );
    }

    private static WorkerNodeStatusResponse toResponse(WorkerStatusResult result) {
        if (!result.available()) {
            return new WorkerNodeStatusResponse(
                    result.endpoint().toString(), false, null, null, result.capabilities(),
                    0, 0, 0, List.of(), List.of(), result.error()
            );
        }
        WorkerStatusResponse status = result.status();
        Instant now = Instant.now();
        return new WorkerNodeStatusResponse(
                result.endpoint().toString(), true, status.getWorkerName(),
                status.getWorkerInstanceId(), result.capabilities(),
                status.getTotalExecuted(), status.getRunningCount(), status.getWaitingCount(),
                status.getRunningTasksList().stream().map(task -> task(task, now, true)).toList(),
                status.getWaitingTasksList().stream().map(task -> task(task, now, false)).toList(),
                null
        );
    }

    private static WorkerTaskStatusResponse task(WorkerTask task, Instant now, boolean running) {
        Instant queuedAt = instant(task.getQueuedAt());
        Instant workStartedAt = task.hasWorkStartedAt() ? instant(task.getWorkStartedAt()) : null;
        Instant elapsedFrom = running && workStartedAt != null ? workStartedAt : queuedAt;
        return new WorkerTaskStatusResponse(
                task.getExecutionId(), task.getAlertId(), task.getAlertName(), queuedAt,
                workStartedAt, Math.max(0, Duration.between(elapsedFrom, now).toMillis())
        );
    }

    private static Instant instant(com.google.protobuf.Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    @Override
    public void close() {
        executor.close();
    }

    private record WorkerStatusResult(
        WorkerEndpoint endpoint,
        Set<WorkerCapability> capabilities,
        WorkerStatusResponse status,
        String error
    ) {

        boolean available() {
            return status != null;
        }
    }
}
