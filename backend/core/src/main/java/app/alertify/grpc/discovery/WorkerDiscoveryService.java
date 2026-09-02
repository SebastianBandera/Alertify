package app.alertify.grpc.discovery;

import static io.grpc.health.v1.HealthCheckResponse.ServingStatus.SERVING;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import app.alertify.grpc.WorkerGrpcHealthProbe;
import app.alertify.grpc.WorkerGrpcProbeResult;
import app.alertify.grpc.WorkerGrpcProperties;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.worker.contract.WorkerCapability;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;

/**
 * Resolves the single worker DNS pool, probes every concrete IP once per cycle
 * and registers each healthy node together with all advertised capabilities.
 */
@Service
public class WorkerDiscoveryService {

    private static final String DNS_RESOLUTION_FAILED = "WORKER_DNS_RESOLUTION_FAILED";
    private static final String DNS_RESOLUTION_RECOVERED = "WORKER_DNS_RESOLUTION_RECOVERED";
    private static final String IP_AVAILABLE = "WORKER_IP_AVAILABLE";
    private static final String IP_DISCOVERED = "WORKER_IP_DISCOVERED";
    private static final String IP_REMOVED = "WORKER_IP_REMOVED";
    private static final String IP_UNAVAILABLE = "WORKER_IP_UNAVAILABLE";

    private final WorkerGrpcProperties properties;
    private final WorkerDnsResolver dnsResolver;
    private final WorkerGrpcHealthProbe healthProbe;
    private final WorkerAvailabilityService availabilityService;
    private final ApplicationEventLogger eventLogger;
    private final Set<WorkerEndpoint> resolvedEndpoints = new HashSet<>();

    private boolean dnsResolutionFailed;

    public WorkerDiscoveryService(WorkerGrpcProperties properties, WorkerDnsResolver dnsResolver, WorkerGrpcHealthProbe healthProbe, WorkerAvailabilityService availabilityService, ApplicationEventLogger eventLogger) {
        this.properties = properties;
        this.dnsResolver = dnsResolver;
        this.healthProbe = healthProbe;
        this.availabilityService = availabilityService;
        this.eventLogger = eventLogger;
    }

    @Scheduled(
        fixedDelayString = "${worker.grpc.discovery.interval:30s}",
        initialDelayString = "${worker.grpc.discovery.initial-delay:0s}"
    )
    public synchronized void refresh() {
        WorkerGrpcProperties.Discovery discovery = properties.discovery();
        if (discovery == null || !discovery.enabled())
            return;

        Set<WorkerEndpoint> currentEndpoints;
        try {
            currentEndpoints = dnsResolver.resolve(properties.host(), properties.port());
            logDnsRecoveryIfNecessary(currentEndpoints);
        } catch (Exception exception) {
            logDnsFailureIfNecessary(exception);
            currentEndpoints = Set.copyOf(resolvedEndpoints);
        }

        Set<WorkerEndpoint> newlyDiscovered = difference(currentEndpoints, resolvedEndpoints);
        Set<WorkerEndpoint> removedFromDns = difference(resolvedEndpoints, currentEndpoints);
        for (WorkerEndpoint endpoint : newlyDiscovered) {
            eventLogger.success(IP_DISCOVERED, endpointData(endpoint, currentEndpoints.size()));
        }
        for (WorkerEndpoint endpoint : removedFromDns) {
            boolean wasAvailable = availabilityService.markUnavailable(endpoint);
            Map<String, Object> data = endpointData(endpoint, currentEndpoints.size());
            data.put("wasAvailable", wasAvailable);
            eventLogger.failure(IP_REMOVED, data);
        }

        resolvedEndpoints.clear();
        resolvedEndpoints.addAll(currentEndpoints);
        for (WorkerEndpoint endpoint : currentEndpoints) {
            checkHealth(endpoint, newlyDiscovered.contains(endpoint), discovery);
        }
    }

    private void checkHealth(WorkerEndpoint endpoint, boolean newlyDiscovered, WorkerGrpcProperties.Discovery discovery) {
        try {
            WorkerGrpcProbeResult result = healthProbe.inspect(endpoint, discovery.healthTimeout());
            ServingStatus status = result.status();
            if (status == SERVING && !result.capabilities().isEmpty()) {
                Set<WorkerCapability> previousCapabilities = availabilityService.markAvailable(endpoint, result.capabilities());
                if (previousCapabilities == null) {
                    Map<String, Object> data = endpointData(endpoint, resolvedEndpoints.size());
                    data.put("status", status.name());
                    data.put("capabilities", capabilityNames(result.capabilities()));
                    data.put("availableCount", availabilityService.availableEndpoints().size());
                    eventLogger.success(IP_AVAILABLE, data);
                }
                return;
            }
            String unavailableStatus = status == SERVING ? "NO_CAPABILITIES" : status.name();
            markUnavailable(endpoint, newlyDiscovered, unavailableStatus, result, null);
        } catch (RuntimeException exception) {
            markUnavailable(endpoint, newlyDiscovered, null, null, exception);
        }
    }

    private void markUnavailable(WorkerEndpoint endpoint, boolean newlyDiscovered, String status, WorkerGrpcProbeResult result, RuntimeException exception) {
        boolean wasAvailable = availabilityService.markUnavailable(endpoint);
        if (!wasAvailable && !newlyDiscovered)
            return;

        Map<String, Object> data = endpointData(endpoint, resolvedEndpoints.size());
        data.put("wasAvailable", wasAvailable);
        data.put("availableCount", availabilityService.availableEndpoints().size());
        if (status != null)
            data.put("status", status);

        if (result != null)
            data.put("capabilities", capabilityNames(result.capabilities()));

        if (exception != null) {
            data.put("exceptionType", exception.getClass().getName());
            if (exception.getMessage() != null)
                data.put("exceptionMessage", exception.getMessage());
        }
        eventLogger.failure(IP_UNAVAILABLE, data);
    }

    private void logDnsFailureIfNecessary(Exception exception) {
        if (dnsResolutionFailed)
            return;

        dnsResolutionFailed = true;
        Map<String, Object> data = dnsData();
        data.put("exceptionType", exception.getClass().getName());
        if (exception.getMessage() != null)
            data.put("exceptionMessage", exception.getMessage());

        eventLogger.failure(DNS_RESOLUTION_FAILED, data);
    }

    private void logDnsRecoveryIfNecessary(Set<WorkerEndpoint> currentEndpoints) {
        if (!dnsResolutionFailed)
            return;

        dnsResolutionFailed = false;
        Map<String, Object> data = dnsData();
        data.put("resolvedCount", currentEndpoints.size());
        eventLogger.success(DNS_RESOLUTION_RECOVERED, data);
    }

    private Map<String, Object> endpointData(WorkerEndpoint endpoint, int resolvedCount) {
        Map<String, Object> data = dnsData();
        data.put("ipAddress", endpoint.ipAddress());
        data.put("port", endpoint.port());
        data.put("resolvedCount", resolvedCount);
        return data;
    }

    private Map<String, Object> dnsData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("dnsName", properties.host());
        return data;
    }

    private static List<String> capabilityNames(Set<WorkerCapability> capabilities) {
        return capabilities.stream()
                .map(WorkerCapability::name)
                .sorted()
                .toList();
    }

    private static Set<WorkerEndpoint> difference(Set<WorkerEndpoint> left, Set<WorkerEndpoint> right) {
        Set<WorkerEndpoint> difference = new HashSet<>(left);
        difference.removeAll(right);
        return difference;
    }
}
