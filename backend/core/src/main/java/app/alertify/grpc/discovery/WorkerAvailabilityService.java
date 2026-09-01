package app.alertify.grpc.discovery;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import app.alertify.worker.contract.WorkerCapability;

/**
 * Publishes one internal registry of healthy worker IPs and supports selecting
 * nodes by one capability or by a cumulative set required by future work.
 */
@Service
public class WorkerAvailabilityService {

    private final Map<WorkerEndpoint, Set<WorkerCapability>> availableWorkers = new ConcurrentHashMap<>();

    public Set<WorkerEndpoint> availableEndpoints() {
        return Set.copyOf(availableWorkers.keySet());
    }

    public Set<AvailableWorker> availableWorkers() {
        return availableWorkers.entrySet().stream()
                .map(entry -> new AvailableWorker(entry.getKey().ipAddress(), entry.getKey().port(), entry.getValue()))
                .collect(Collectors.toUnmodifiableSet());
    }

    public Set<AvailableWorker> availableWorkersWith(WorkerCapability capability) {
        return availableWorkersWithAll(Set.of(capability));
    }

    public Set<AvailableWorker> availableWorkersWithAll(Set<WorkerCapability> requiredCapabilities) {
        if (requiredCapabilities == null)
            throw new IllegalArgumentException("requiredCapabilities must not be null");

        Set<WorkerCapability> required = Set.copyOf(requiredCapabilities);
        return availableWorkers.entrySet().stream()
                .filter(entry -> entry.getValue().containsAll(required))
                .map(entry -> new AvailableWorker(entry.getKey().ipAddress(), entry.getKey().port(), entry.getValue()))
                .collect(Collectors.toUnmodifiableSet());
    }

    Set<WorkerCapability> markAvailable(WorkerEndpoint endpoint, Set<WorkerCapability> capabilities) {
        return availableWorkers.put(endpoint, Set.copyOf(capabilities));
    }

    boolean markUnavailable(WorkerEndpoint endpoint) {
        return availableWorkers.remove(endpoint) != null;
    }
}
