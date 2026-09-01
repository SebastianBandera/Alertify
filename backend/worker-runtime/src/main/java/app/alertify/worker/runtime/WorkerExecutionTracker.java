package app.alertify.worker.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.LongAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import app.alertify.worker.grpc.ExecuteAlertRequest;

class WorkerExecutionTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkerExecutionTracker.class);

    private final Semaphore semaphore;
    private final LongAdder totalExecuted = new LongAdder();
    private final Map<String, TaskState> waiting = new ConcurrentHashMap<>();
    private final Map<String, TaskState> running = new ConcurrentHashMap<>();

    WorkerExecutionTracker(WorkerRuntimeProperties properties) {
        if (properties.maxConcurrentAlerts() <= 0)
            throw new IllegalArgumentException("alertify.worker.max-concurrent-alerts must be positive");

        semaphore = new Semaphore(properties.maxConcurrentAlerts(), true);
    }

    Permit acquire(ExecuteAlertRequest request, Instant queuedAt) throws InterruptedException {
        TaskState task = new TaskState(
                request.getExecutionId(), request.getAlertId(), request.getAlertName(), queuedAt, null
        );

        waiting.put(request.getExecutionId(), task);
        
        try {
            if (!semaphore.tryAcquire()) {
                LOGGER.info("Alert execution is waiting for a permit: executionId={}, alertId={}, alertName={}", request.getExecutionId(), request.getAlertId(), request.getAlertName());
                semaphore.acquire();
            }
        } catch (InterruptedException exception) {
            waiting.remove(request.getExecutionId());
            throw exception;
        }

        Instant workStartedAt = Instant.now();
        waiting.remove(request.getExecutionId());
        running.put(request.getExecutionId(), task.withWorkStartedAt(workStartedAt));
        
        LOGGER.info("Alert execution started: executionId={}, alertId={}, alertName={}, waitedMs={}", request.getExecutionId(), request.getAlertId(), request.getAlertName(), Duration.between(queuedAt, workStartedAt).toMillis());
        
        return new Permit(request.getExecutionId(), workStartedAt);
    }

    long totalExecuted() {
        return totalExecuted.sum();
    }

    List<TaskState> waitingTasks() {
        return snapshots(waiting);
    }

    List<TaskState> runningTasks() {
        return snapshots(running);
    }

    private static List<TaskState> snapshots(Map<String, TaskState> tasks) {
        return tasks.values().stream()
                .sorted(Comparator.comparing(TaskState::queuedAt))
                .toList();
    }

    record TaskState(
        String executionId,
        long alertId,
        String alertName,
        Instant queuedAt,
        Instant workStartedAt
    ) {

        TaskState withWorkStartedAt(Instant value) {
            return new TaskState(executionId, alertId, alertName, queuedAt, value);
        }
    }

    final class Permit implements AutoCloseable {

        private final String executionId;
        private final Instant workStartedAt;
        private boolean closed;

        private Permit(String executionId, Instant workStartedAt) {
            this.executionId = executionId;
            this.workStartedAt = workStartedAt;
        }

        Instant workStartedAt() {
            return workStartedAt;
        }

        @Override
        public void close() {
            if (closed)
                return;

            closed = true;
            running.remove(executionId);
            totalExecuted.increment();
            semaphore.release();
        }
    }
}
