package app.alertify.grpc.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

import app.alertify.worker.contract.WorkerCapability;

class WorkerAvailabilityServiceTest {

    @Test
    void selectsWorkersByOneCapabilityOrAnEntireRequiredSet() {
        WorkerAvailabilityService service = new WorkerAvailabilityService();
        service.markAvailable(new WorkerEndpoint("10.0.0.2", 9090), Set.of(WorkerCapability.STANDARD));
        service.markAvailable(new WorkerEndpoint("10.0.0.3", 9090), Set.of(WorkerCapability.PLAYWRIGHT));
        service.markAvailable(new WorkerEndpoint("10.0.0.4", 9090), Set.of(WorkerCapability.STANDARD, WorkerCapability.PLAYWRIGHT));

        assertThat(service.availableWorkersWith(WorkerCapability.STANDARD))
                .extracting(AvailableWorker::ipAddress)
                .containsExactlyInAnyOrder("10.0.0.2", "10.0.0.4");
        assertThat(service.availableWorkersWith(WorkerCapability.PLAYWRIGHT))
                .extracting(AvailableWorker::ipAddress)
                .containsExactlyInAnyOrder("10.0.0.3", "10.0.0.4");
        assertThat(service.availableWorkersWithAll(Set.of(WorkerCapability.STANDARD, WorkerCapability.PLAYWRIGHT)))
                .singleElement()
                .satisfies(worker -> {
                    assertThat(worker.ipAddress()).isEqualTo("10.0.0.4");
                    assertThat(worker.capabilities()).containsExactlyInAnyOrder(WorkerCapability.STANDARD, WorkerCapability.PLAYWRIGHT);
                });
    }
}
