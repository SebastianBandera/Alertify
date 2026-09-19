package app.alertify.dashboard;

import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Component;

/**
 * In-memory view of the alert executions currently running on workers. The
 * orchestrator owns every execution of this backend instance, so the registry
 * is complete without persisting an "in progress" row.
 */
@Component
public class AlertExecutionRunningRegistry {

    private final ConcurrentMap<Long, ConcurrentMap<UUID, Instant>> running = new ConcurrentHashMap<>();

    public void started(long alertId, UUID executionId, Instant startedAt) {
        running.computeIfAbsent(alertId, _ -> new ConcurrentHashMap<>()).put(executionId, startedAt);
    }

    public void finished(long alertId, UUID executionId) {
        running.computeIfPresent(alertId, (_, executions) -> {
            executions.remove(executionId);
            return executions.isEmpty() ? null : executions;
        });
    }

    /** Start of the earliest execution still in progress for the alert, if any. */
    public Optional<Instant> runningSince(long alertId) {
        ConcurrentMap<UUID, Instant> executions = running.get(alertId);
        if (executions == null)
            return Optional.empty();

        return executions.values().stream().min(Comparator.naturalOrder());
    }
}
