package app.alertify.grpc.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import app.alertify.grpc.AlertWorkerClient;
import app.alertify.grpc.WorkerGrpcProperties;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.worker.contract.WorkerCapability;
import app.alertify.worker.grpc.WorkerStatusResponse;

@ExtendWith(MockitoExtension.class)
class WorkerStatusServiceTest {

    private static final WorkerEndpoint FIRST = new WorkerEndpoint("10.0.0.2", 9090);
    private static final WorkerEndpoint SECOND = new WorkerEndpoint("10.0.0.3", 9090);
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    @Mock private AlertWorkerClient client;
    @Mock private ApplicationEventLogger eventLogger;

    private WorkerAvailabilityService availabilityService;
    private WorkerStatusService service;

    @BeforeEach
    void setUp() {
        availabilityService = new WorkerAvailabilityService();
        availabilityService.markAvailable(FIRST, Set.of(WorkerCapability.STANDARD));
        availabilityService.markAvailable(SECOND, Set.of(WorkerCapability.STANDARD));
        service = new WorkerStatusService(availabilityService, client, properties(), eventLogger);
    }

    @AfterEach
    void closeService() {
        service.close();
    }

    @Test
    void selectsTheCompatibleWorkerWithTheLowestCurrentLoad() {
        when(client.status(FIRST, TIMEOUT)).thenReturn(status(2, 1));
        when(client.status(SECOND, TIMEOUT)).thenReturn(status(1, 0));

        try (WorkerReservation reservation = service.reserve(WorkerCapability.STANDARD)) {
            assertThat(reservation.worker().endpoint()).isEqualTo(SECOND);
            assertThat(reservation.worker().currentLoad()).isEqualTo(1);
        }
    }

    @Test
    void includesLocalReservationsWhenConcurrentDispatchesSeeTheSameWorkerLoad() {
        when(client.status(FIRST, TIMEOUT)).thenReturn(status(0, 0));
        when(client.status(SECOND, TIMEOUT)).thenReturn(status(0, 0));

        try (WorkerReservation first = service.reserve(WorkerCapability.STANDARD);
                WorkerReservation second = service.reserve(WorkerCapability.STANDARD)) {
            assertThat(first.worker().endpoint()).isEqualTo(FIRST);
            assertThat(second.worker().endpoint()).isEqualTo(SECOND);
        }
    }

    @Test
    void rotatesBetweenEquallyLoadedWorkersAcrossCompletedReservations() {
        when(client.status(FIRST, TIMEOUT)).thenReturn(status(0, 0));
        when(client.status(SECOND, TIMEOUT)).thenReturn(status(0, 0));

        try (WorkerReservation first = service.reserve(WorkerCapability.STANDARD)) {
            assertThat(first.worker().endpoint()).isEqualTo(FIRST);
        }
        try (WorkerReservation second = service.reserve(WorkerCapability.STANDARD)) {
            assertThat(second.worker().endpoint()).isEqualTo(SECOND);
        }
        try (WorkerReservation third = service.reserve(WorkerCapability.STANDARD)) {
            assertThat(third.worker().endpoint()).isEqualTo(FIRST);
        }
    }

    private static WorkerStatusResponse status(int running, int waiting) {
        return WorkerStatusResponse.newBuilder()
                .setWorkerName("worker")
                .setWorkerInstanceId("15d5376a-e386-48fe-a089-3d8e597bc29a")
                .addCapabilities(WorkerCapability.STANDARD.name())
                .setRunningCount(running)
                .setWaitingCount(waiting)
                .build();
    }

    private static WorkerGrpcProperties properties() {
        return new WorkerGrpcProperties(
                "worker", 9090,
                new WorkerGrpcProperties.Tls(false, null, null, null, null),
                new WorkerGrpcProperties.Discovery(
                        true, Duration.ofSeconds(30), Duration.ZERO, TIMEOUT
                ),
                new WorkerGrpcProperties.Execution(
                        Duration.ofMinutes(30), Duration.ofSeconds(30), Path.of("src")
                )
        );
    }
}
