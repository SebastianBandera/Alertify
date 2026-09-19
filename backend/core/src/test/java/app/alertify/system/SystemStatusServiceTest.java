package app.alertify.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import app.alertify.alerts.execution.CronQuietHoursService;
import app.alertify.alerts.execution.MaintenanceModeService;
import app.alertify.grpc.api.WorkerNodeStatusResponse;
import app.alertify.grpc.api.WorkerTaskStatusResponse;
import app.alertify.grpc.discovery.WorkerStatusService;

@ExtendWith(MockitoExtension.class)
class SystemStatusServiceTest {

    @Mock private MaintenanceModeService maintenanceModeService;
    @Mock private CronQuietHoursService cronQuietHoursService;
    @Mock private WorkerStatusService workerStatusService;
    @InjectMocks private SystemStatusService service;

    @Test
    void countsRunningProceduresFromTheDedicatedWorkerInventory() {
        WorkerTaskStatusResponse alert = task("alert-execution", "ALERT");
        WorkerTaskStatusResponse procedure = task("procedure-execution", "PROCEDURE");
        WorkerNodeStatusResponse worker = new WorkerNodeStatusResponse(
                "worker:9090", true, "worker", "instance", Instant.now(), Set.of(),
                0, 1, 0, 1, List.of(alert), List.of(), 0, 1, List.of(procedure), null
        );
        when(workerStatusService.realtimeStatus()).thenReturn(List.of(worker));

        var summary = service.realtimeSummary();

        assertThat(summary.activeAlertExecutions()).isEqualTo(1);
        assertThat(summary.activeProcedureExecutions()).isEqualTo(1);
        assertThat(summary.waitingProcedureExecutions()).isZero();
    }

    private static WorkerTaskStatusResponse task(String executionId, String kind) {
        Instant now = Instant.now();
        return new WorkerTaskStatusResponse(executionId, kind, 1, kind, null, 0, now, now, 0);
    }
}
