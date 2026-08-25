package app.alertify.grpc.discovery;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

/**
 * Publishes the immutable snapshot of worker endpoints that most recently
 * passed their direct gRPC health check. Internal backend services can use this
 * registry when selecting a worker for future operations.
 */
@Service
public class WorkerStandardAvailabilityService {

    private final Set<WorkerStandardEndpoint> availableEndpoints = ConcurrentHashMap.newKeySet();

    public Set<WorkerStandardEndpoint> availableEndpoints() {
        return Set.copyOf(availableEndpoints);
    }

    boolean markAvailable(WorkerStandardEndpoint endpoint) {
        return availableEndpoints.add(endpoint);
    }

    boolean markUnavailable(WorkerStandardEndpoint endpoint) {
        return availableEndpoints.remove(endpoint);
    }
}
