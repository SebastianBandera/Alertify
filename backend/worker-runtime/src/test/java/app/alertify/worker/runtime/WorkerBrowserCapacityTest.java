package app.alertify.worker.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import app.alertify.worker.contract.WorkerCapability;
import app.alertify.worker.grpc.ExecuteAlertRequest;

class WorkerBrowserCapacityTest {
    @Test
    void proceduresRemainIndependentWhenAllBrowserSlotsAreOccupied() throws Exception {
        var properties = new WorkerRuntimeProperties("procedure-test", 0, 134217728, Duration.ofSeconds(1), Set.of(WorkerCapability.PLAYWRIGHT), 2,
                Path.of("target", "procedure-capacity"), null, new WorkerRuntimeProperties.Tls(false, null, null, null));
        var tracker = new WorkerExecutionTracker(properties);
        var request = app.alertify.worker.grpc.ExecuteProcedureRequest.newBuilder().setExecutionId("value-1").setProcedureId(1).build();
        try (var browser = tracker.tryBrowserSession("browser"); var secondBrowser = tracker.tryBrowserSession("second")) {
            var procedure = tracker.startProcedure(request, java.time.Instant.now());
            assertThat(tracker.runningTasks()).hasSize(2);
            assertThat(tracker.runningProcedureTasks()).hasSize(1);
            procedure.close();
            procedure.close();
            assertThat(tracker.runningTasks()).hasSize(2);
            assertThat(tracker.totalExecuted()).isZero();
            assertThat(tracker.totalExecutedProcedures()).isEqualTo(1);
        }
        assertThat(tracker.runningTasks()).isEmpty();
    }

    @Test
    void sharesTheAlertLimitAndReleasesExactlyOnceWithoutIncrementingAlertTotals() throws Exception {
        var properties = new WorkerRuntimeProperties("browser-test", 0, 134217728, Duration.ofSeconds(1), Set.of(WorkerCapability.PLAYWRIGHT), 2,
                Path.of("target", "browser-capacity"), null, new WorkerRuntimeProperties.Tls(false, null, null, null));
        var tracker = new WorkerExecutionTracker(properties);
        var browser = tracker.tryBrowserSession("one");
        var alert = tracker.acquire(ExecuteAlertRequest.newBuilder().setExecutionId("alert-one").setAlertId(1).setAlertName("alert").build(), ExecutionTimeline.start("alert-one"));
        assertThat(tracker.runningTasks()).hasSize(2);
        assertThatThrownBy(() -> tracker.tryBrowserSession("two")).hasMessage("PLAYWRIGHT_CAPACITY_FULL");
        browser.close();
        browser.close();
        try (var next = tracker.tryBrowserSession("two")) {
            assertThatThrownBy(() -> tracker.tryBrowserSession("three")).hasMessage("PLAYWRIGHT_CAPACITY_FULL");
            assertThat(tracker.totalExecuted()).isZero();
        }
        alert.close();
        assertThat(tracker.runningTasks()).isEmpty();
        assertThat(tracker.totalExecuted()).isEqualTo(1);
    }
}
