package app.alertify.grpc.discovery;

import static io.grpc.health.v1.HealthCheckResponse.ServingStatus.SERVING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import app.alertify.grpc.WorkerStandardGrpcHealthProbe;
import app.alertify.grpc.WorkerStandardGrpcProperties;
import app.alertify.logging.ApplicationEventLogger;

@ExtendWith(MockitoExtension.class)
class WorkerStandardDiscoveryServiceTest {

    private static final WorkerStandardEndpoint FIRST = new WorkerStandardEndpoint("10.0.0.2", 9090);
    private static final WorkerStandardEndpoint SECOND = new WorkerStandardEndpoint("10.0.0.3", 9090);

    @Mock private WorkerStandardDnsResolver dnsResolver;
    @Mock private WorkerStandardGrpcHealthProbe healthProbe;
    @Mock private ApplicationEventLogger eventLogger;

    @Test
    void removesFailedWorkersImmediatelyAndRestoresThemOnTheNextSuccessfulCycle() throws Exception {
        WorkerStandardAvailabilityService availabilityService = new WorkerStandardAvailabilityService();
        WorkerStandardDiscoveryService service = service(availabilityService);
        when(dnsResolver.resolve("worker-standard", 9090)).thenReturn(
            Set.of(FIRST, SECOND), Set.of(FIRST, SECOND), Set.of(FIRST, SECOND), Set.of(FIRST)
        );
        when(healthProbe.check(eq(FIRST), eq(Duration.ofSeconds(2)))).thenReturn(SERVING);
        when(healthProbe.check(eq(SECOND), eq(Duration.ofSeconds(2))))
                .thenReturn(SERVING)
                .thenThrow(new IllegalStateException("worker unavailable"))
                .thenReturn(SERVING);

        service.refresh();
        assertThat(availabilityService.availableEndpoints()).containsExactlyInAnyOrder(FIRST, SECOND);

        service.refresh();
        assertThat(availabilityService.availableEndpoints()).containsExactly(FIRST);

        service.refresh();
        assertThat(availabilityService.availableEndpoints()).containsExactlyInAnyOrder(FIRST, SECOND);

        service.refresh();
        assertThat(availabilityService.availableEndpoints()).containsExactly(FIRST);

        verify(eventLogger, times(2)).success(eq("WORKER_IP_DISCOVERED"), anyMap());
        verify(eventLogger, times(3)).success(eq("WORKER_IP_AVAILABLE"), anyMap());
        verify(eventLogger).failure(eq("WORKER_IP_UNAVAILABLE"), anyMap());
        verify(eventLogger).failure(eq("WORKER_IP_REMOVED"), anyMap());
    }

    @Test
    void continuesCheckingKnownWorkersWhenDnsResolutionTemporarilyFails() throws Exception {
        WorkerStandardAvailabilityService availabilityService = new WorkerStandardAvailabilityService();
        WorkerStandardDiscoveryService service = service(availabilityService);
        when(dnsResolver.resolve("worker-standard", 9090))
                .thenReturn(Set.of(FIRST))
                .thenThrow(new UnknownHostException("worker-standard"));
        when(healthProbe.check(FIRST, Duration.ofSeconds(2)))
                .thenReturn(SERVING)
                .thenThrow(new IllegalStateException("worker unavailable"));

        service.refresh();
        service.refresh();

        assertThat(availabilityService.availableEndpoints()).isEmpty();
        verify(healthProbe, times(2)).check(FIRST, Duration.ofSeconds(2));
        verify(eventLogger).failure(eq("WORKER_DNS_RESOLUTION_FAILED"), anyMap());
        verify(eventLogger).failure(eq("WORKER_IP_UNAVAILABLE"), anyMap());
    }

    private WorkerStandardDiscoveryService service(WorkerStandardAvailabilityService availabilityService) {
        WorkerStandardGrpcProperties.Discovery discovery = new WorkerStandardGrpcProperties.Discovery(
            true, Duration.ofSeconds(30), Duration.ZERO, Duration.ofSeconds(2)
        );
        WorkerStandardGrpcProperties properties = new WorkerStandardGrpcProperties(
            "worker-standard", 9090, discovery
        );
        return new WorkerStandardDiscoveryService(properties, dnsResolver, healthProbe, availabilityService, eventLogger);
    }
}
