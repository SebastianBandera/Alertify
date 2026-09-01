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

import app.alertify.grpc.WorkerGrpcHealthProbe;
import app.alertify.grpc.WorkerGrpcProbeResult;
import app.alertify.grpc.WorkerGrpcProperties;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.worker.contract.WorkerCapability;

@ExtendWith(MockitoExtension.class)
class WorkerDiscoveryServiceTest {

    private static final WorkerEndpoint FIRST = new WorkerEndpoint("10.0.0.2", 9090);
    private static final WorkerEndpoint SECOND = new WorkerEndpoint("10.0.0.3", 9090);

    @Mock private WorkerDnsResolver dnsResolver;
    @Mock private WorkerGrpcHealthProbe healthProbe;
    @Mock private ApplicationEventLogger eventLogger;

    @Test
    void registersEachIpWithItsOwnCumulativeCapabilities() throws Exception {
        WorkerAvailabilityService availabilityService = new WorkerAvailabilityService();
        WorkerDiscoveryService service = service(availabilityService);
        when(dnsResolver.resolve("worker", 9090)).thenReturn(Set.of(FIRST, SECOND));
        when(healthProbe.inspect(FIRST, Duration.ofSeconds(2))).thenReturn(serving(WorkerCapability.STANDARD));
        when(healthProbe.inspect(SECOND, Duration.ofSeconds(2))).thenReturn(serving(WorkerCapability.STANDARD, WorkerCapability.PLAYWRIGHT));

        service.refresh();

        assertThat(availabilityService.availableWorkersWith(WorkerCapability.STANDARD))
                .extracting(AvailableWorker::ipAddress)
                .containsExactlyInAnyOrder("10.0.0.2", "10.0.0.3");
        assertThat(availabilityService.availableWorkersWithAll(Set.of(WorkerCapability.STANDARD, WorkerCapability.PLAYWRIGHT)))
                .extracting(AvailableWorker::ipAddress)
                .containsExactly("10.0.0.3");
        verify(eventLogger, times(2)).success(eq("WORKER_IP_DISCOVERED"), anyMap());
        verify(eventLogger, times(2)).success(eq("WORKER_IP_AVAILABLE"), anyMap());
    }

    @Test
    void removesFailedWorkersImmediatelyAndRestoresThemOnTheNextSuccessfulCycle() throws Exception {
        WorkerAvailabilityService availabilityService = new WorkerAvailabilityService();
        WorkerDiscoveryService service = service(availabilityService);
        when(dnsResolver.resolve("worker", 9090)).thenReturn(Set.of(FIRST), Set.of(FIRST), Set.of(FIRST));
        when(healthProbe.inspect(FIRST, Duration.ofSeconds(2)))
                .thenReturn(serving(WorkerCapability.STANDARD))
                .thenThrow(new IllegalStateException("worker unavailable"))
                .thenReturn(serving(WorkerCapability.PLAYWRIGHT));

        service.refresh();
        assertThat(availabilityService.availableEndpoints()).containsExactly(FIRST);

        service.refresh();
        assertThat(availabilityService.availableEndpoints()).isEmpty();

        service.refresh();
        assertThat(availabilityService.availableWorkersWith(WorkerCapability.PLAYWRIGHT))
                .extracting(AvailableWorker::ipAddress)
                .containsExactly("10.0.0.2");

        verify(eventLogger, times(2)).success(eq("WORKER_IP_AVAILABLE"), anyMap());
        verify(eventLogger).failure(eq("WORKER_IP_UNAVAILABLE"), anyMap());
    }

    @Test
    void continuesCheckingKnownWorkersWhenDnsResolutionTemporarilyFails() throws Exception {
        WorkerAvailabilityService availabilityService = new WorkerAvailabilityService();
        WorkerDiscoveryService service = service(availabilityService);
        when(dnsResolver.resolve("worker", 9090))
                .thenReturn(Set.of(FIRST))
                .thenThrow(new UnknownHostException("worker"));
        when(healthProbe.inspect(FIRST, Duration.ofSeconds(2)))
                .thenReturn(serving(WorkerCapability.STANDARD))
                .thenThrow(new IllegalStateException("worker unavailable"));

        service.refresh();
        service.refresh();

        assertThat(availabilityService.availableEndpoints()).isEmpty();
        verify(healthProbe, times(2)).inspect(FIRST, Duration.ofSeconds(2));
        verify(eventLogger).failure(eq("WORKER_DNS_RESOLUTION_FAILED"), anyMap());
        verify(eventLogger).failure(eq("WORKER_IP_UNAVAILABLE"), anyMap());
    }

    @Test
    void rejectsAHealthyWorkerThatAdvertisesNoKnownCapability() throws Exception {
        WorkerAvailabilityService availabilityService = new WorkerAvailabilityService();
        WorkerDiscoveryService service = service(availabilityService);
        when(dnsResolver.resolve("worker", 9090)).thenReturn(Set.of(FIRST));
        when(healthProbe.inspect(FIRST, Duration.ofSeconds(2))).thenReturn(serving());

        service.refresh();

        assertThat(availabilityService.availableEndpoints()).isEmpty();
        verify(eventLogger).failure(eq("WORKER_IP_UNAVAILABLE"), anyMap());
    }

    private WorkerDiscoveryService service(WorkerAvailabilityService availabilityService) {
        WorkerGrpcProperties.Discovery discovery = new WorkerGrpcProperties.Discovery(
            true, Duration.ofSeconds(30), Duration.ZERO, Duration.ofSeconds(2)
        );
        WorkerGrpcProperties.Tls tls = new WorkerGrpcProperties.Tls(false, null, null, null, null);
        WorkerGrpcProperties.Execution execution = new WorkerGrpcProperties.Execution(
                Duration.ofMinutes(30), Duration.ofSeconds(30), java.nio.file.Path.of("src")
        );
        WorkerGrpcProperties properties = new WorkerGrpcProperties(
                "worker", 9090, tls, discovery, execution
        );
        return new WorkerDiscoveryService(properties, dnsResolver, healthProbe, availabilityService, eventLogger);
    }

    private static WorkerGrpcProbeResult serving(WorkerCapability... capabilities) {
        return new WorkerGrpcProbeResult(SERVING, Set.of(capabilities));
    }
}
