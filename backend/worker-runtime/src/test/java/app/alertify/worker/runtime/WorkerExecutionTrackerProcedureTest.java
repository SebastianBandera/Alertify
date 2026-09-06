package app.alertify.worker.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;

import app.alertify.worker.contract.WorkerCapability;
import app.alertify.worker.grpc.ExecuteAlertRequest;
import app.alertify.worker.grpc.ExecuteProcedureRequest;

class WorkerExecutionTrackerProcedureTest {

    @Test
    void startsAProcedureWhileTheOnlyAlertSemaphorePermitIsOccupied() throws Exception {
        WorkerExecutionTracker tracker = new WorkerExecutionTracker(properties());
        WorkerExecutionTracker.Permit alertPermit = tracker.acquire(
                ExecuteAlertRequest.newBuilder().setExecutionId("alert-1").setAlertId(1)
                        .setAlertName("parent").build(), Instant.now()
        );

        WorkerExecutionTracker.ProcedurePermit procedurePermit = tracker.startProcedure(
                ExecuteProcedureRequest.newBuilder().setExecutionId("procedure-1").setProcedureId(2)
                        .setProcedureName("nested").setParentExecutionId("alert-1").setDepth(1).build(),
                Instant.now()
        );

        assertThat(tracker.runningTasks()).hasSize(1);
        assertThat(tracker.runningProcedureTasks()).hasSize(1);
        procedurePermit.close();
        alertPermit.close();
        assertThat(tracker.totalExecuted()).isEqualTo(1);
        assertThat(tracker.totalExecutedProcedures()).isEqualTo(1);
    }

    private static WorkerRuntimeProperties properties() {
        return new WorkerRuntimeProperties(
                "test-worker", 0, Duration.ofSeconds(1), Set.of(WorkerCapability.STANDARD), 1,
                Path.of("target", "compiled-test"), null,
                new WorkerRuntimeProperties.Tls(false, null, null, null)
        );
    }
}
