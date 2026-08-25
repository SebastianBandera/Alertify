package app.alertify.grpc.discovery;

import static io.grpc.health.v1.HealthCheckResponse.ServingStatus.SERVING;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import app.alertify.grpc.WorkerStandardGrpcHealthProbe;
import app.alertify.grpc.WorkerStandardGrpcProperties;
import app.alertify.logging.ApplicationEventLogger;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;

/**
 * Periodically resolves the shared worker DNS name, health-checks every
 * concrete IP once per cycle and updates the internally published availability
 * registry without making backend startup depend on worker availability.
 */
@Service
public class WorkerStandardDiscoveryService {

    private static final String DNS_RESOLUTION_FAILED = "WORKER_DNS_RESOLUTION_FAILED";
    private static final String DNS_RESOLUTION_RECOVERED = "WORKER_DNS_RESOLUTION_RECOVERED";
    private static final String IP_AVAILABLE = "WORKER_IP_AVAILABLE";
    private static final String IP_DISCOVERED = "WORKER_IP_DISCOVERED";
    private static final String IP_REMOVED = "WORKER_IP_REMOVED";
    private static final String IP_UNAVAILABLE = "WORKER_IP_UNAVAILABLE";

    private final WorkerStandardGrpcProperties properties;
    private final WorkerStandardDnsResolver dnsResolver;
    private final WorkerStandardGrpcHealthProbe healthProbe;
    private final WorkerStandardAvailabilityService availabilityService;
    private final ApplicationEventLogger eventLogger;
    private final Set<WorkerStandardEndpoint> resolvedEndpoints = new HashSet<>();

    private boolean dnsResolutionFailed;

    public WorkerStandardDiscoveryService(WorkerStandardGrpcProperties properties, WorkerStandardDnsResolver dnsResolver, WorkerStandardGrpcHealthProbe healthProbe, WorkerStandardAvailabilityService availabilityService, ApplicationEventLogger eventLogger) {
        this.properties = properties;
        this.dnsResolver = dnsResolver;
        this.healthProbe = healthProbe;
        this.availabilityService = availabilityService;
        this.eventLogger = eventLogger;
    }

    @Scheduled(
        fixedDelayString = "${worker-standard.grpc.discovery.interval:30s}",
        initialDelayString = "${worker-standard.grpc.discovery.initial-delay:0s}"
    )
    public synchronized void refresh() {
        WorkerStandardGrpcProperties.Discovery discovery = properties.discovery();
        if (discovery == null || !discovery.enabled())
            return;

        Set<WorkerStandardEndpoint> currentEndpoints;
        try {
            currentEndpoints = dnsResolver.resolve(properties.host(), properties.port());
            logDnsRecoveryIfNecessary(currentEndpoints);
        } catch (Exception exception) {
            logDnsFailureIfNecessary(exception);
            currentEndpoints = Set.copyOf(resolvedEndpoints);
        }

        Set<WorkerStandardEndpoint> newlyDiscovered = difference(currentEndpoints, resolvedEndpoints);
        Set<WorkerStandardEndpoint> removedFromDns = difference(resolvedEndpoints, currentEndpoints);
        for (WorkerStandardEndpoint endpoint : newlyDiscovered) {
            eventLogger.success(IP_DISCOVERED, endpointData(endpoint, currentEndpoints.size()));
        }
        for (WorkerStandardEndpoint endpoint : removedFromDns) {
            boolean wasAvailable = availabilityService.markUnavailable(endpoint);
            Map<String, Object> data = endpointData(endpoint, currentEndpoints.size());
            data.put("wasAvailable", wasAvailable);
            eventLogger.failure(IP_REMOVED, data);
        }

        resolvedEndpoints.clear();
        resolvedEndpoints.addAll(currentEndpoints);
        for (WorkerStandardEndpoint endpoint : currentEndpoints) {
            checkHealth(endpoint, newlyDiscovered.contains(endpoint), discovery);
        }
    }

    private void checkHealth(WorkerStandardEndpoint endpoint, boolean newlyDiscovered, WorkerStandardGrpcProperties.Discovery discovery) {
        try {
            ServingStatus status = healthProbe.check(endpoint, discovery.healthTimeout());
            if (status == SERVING) {
                if (availabilityService.markAvailable(endpoint)) {
                    Map<String, Object> data = endpointData(endpoint, resolvedEndpoints.size());
                    data.put("status", status.name());
                    data.put("availableCount", availabilityService.availableEndpoints().size());
                    eventLogger.success(IP_AVAILABLE, data);
                }
                return;
            }
            markUnavailable(endpoint, newlyDiscovered, status.name(), null);
        } catch (RuntimeException exception) {
            markUnavailable(endpoint, newlyDiscovered, null, exception);
        }
    }

    private void markUnavailable(WorkerStandardEndpoint endpoint, boolean newlyDiscovered, String status, RuntimeException exception) {
        boolean wasAvailable = availabilityService.markUnavailable(endpoint);
        if (!wasAvailable && !newlyDiscovered)
            return;

        Map<String, Object> data = endpointData(endpoint, resolvedEndpoints.size());
        data.put("wasAvailable", wasAvailable);
        data.put("availableCount", availabilityService.availableEndpoints().size());
        if (status != null)
            data.put("status", status);
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

    private void logDnsRecoveryIfNecessary(Set<WorkerStandardEndpoint> currentEndpoints) {
        if (!dnsResolutionFailed)
            return;

        dnsResolutionFailed = false;
        Map<String, Object> data = dnsData();
        data.put("resolvedCount", currentEndpoints.size());
        eventLogger.success(DNS_RESOLUTION_RECOVERED, data);
    }

    private Map<String, Object> endpointData(WorkerStandardEndpoint endpoint, int resolvedCount) {
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

    private static Set<WorkerStandardEndpoint> difference(Set<WorkerStandardEndpoint> left, Set<WorkerStandardEndpoint> right) {
        Set<WorkerStandardEndpoint> difference = new HashSet<>(left);
        difference.removeAll(right);
        return difference;
    }
}
