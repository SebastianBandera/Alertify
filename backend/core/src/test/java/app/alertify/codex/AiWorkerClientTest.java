package app.alertify.codex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.junit.jupiter.api.Test;

import app.alertify.grpc.WorkerGrpcChannelFactory;
import app.alertify.grpc.discovery.AvailableWorker;
import app.alertify.grpc.discovery.WorkerAvailabilityService;
import app.alertify.worker.contract.WorkerCapability;

class AiWorkerClientTest {

    private static final Set<WorkerCapability> CAPABILITIES = Set.of(WorkerCapability.AI, WorkerCapability.CODEX);

    private final WorkerAvailabilityService availability = mock(WorkerAvailabilityService.class);
    private final AiWorkerClient client = new AiWorkerClient(availability, mock(WorkerGrpcChannelFactory.class));

    @Test
    void requiresExactlyOneWorkerWithBothAiCapabilities() {
        when(availability.availableWorkersWithAll(CAPABILITIES)).thenReturn(Set.of());
        assertThatThrownBy(client::endpoint).isInstanceOf(AiWorkerException.class)
                .extracting("code").isEqualTo("AI_WORKER_UNAVAILABLE");

        AvailableWorker first = worker("10.0.0.4");
        AvailableWorker second = worker("10.0.0.5");
        when(availability.availableWorkersWithAll(CAPABILITIES)).thenReturn(Set.of(first, second));
        assertThatThrownBy(client::endpoint).isInstanceOf(AiWorkerException.class)
                .extracting("code").isEqualTo("AI_WORKER_AMBIGUOUS");

        when(availability.availableWorkersWithAll(CAPABILITIES)).thenReturn(Set.of(first));
        assertThat(client.endpoint().ipAddress()).isEqualTo("10.0.0.4");
        assertThat(client.endpoint().port()).isEqualTo(9090);
    }

    private static AvailableWorker worker(String address) {
        return new AvailableWorker(address, 9090, CAPABILITIES);
    }
}
