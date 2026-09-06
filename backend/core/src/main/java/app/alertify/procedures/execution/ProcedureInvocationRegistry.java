package app.alertify.procedures.execution;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Component;

/** Tracks parents that are currently allowed to invoke their configured handles. */
@Component
public class ProcedureInvocationRegistry {
    private final ConcurrentMap<UUID, Instant> active = new ConcurrentHashMap<>();

    public void register(UUID executionId, Instant deadline) {
        if (active.putIfAbsent(executionId, deadline) != null)
            throw new IllegalStateException("Execution is already registered: " + executionId);
    }

    public void unregister(UUID executionId) {
        active.remove(executionId);
    }

    public boolean isActive(UUID executionId, Instant now) {
        Instant deadline = active.get(executionId);
        return deadline != null && deadline.isAfter(now);
    }
}
