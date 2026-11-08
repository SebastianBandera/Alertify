package app.alertify.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.management.RuntimeMXBean;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import app.alertify.worker.contract.WorkerResourceMonitor;
import app.alertify.worker.grpc.WorkerResourceUsage;

class BackendStatusServiceTest {

    @Test
    void keepsTheInstanceIdentityWhileReportingLiveMeasurementsAndJvmUptime() {
        WorkerResourceMonitor resources = mock(WorkerResourceMonitor.class);
        RuntimeMXBean runtime = mock(RuntimeMXBean.class);
        when(runtime.getStartTime()).thenReturn(1_700_000_000_000L);
        when(runtime.getUptime()).thenReturn(5_000L, 6_000L);
        when(resources.usage()).thenReturn(WorkerResourceUsage.newBuilder()
                .setMemoryUsedBytes(123).setMemoryMaxBytes(1024).setHeapUsedBytes(64).setHeapMaxBytes(512)
                .setCpuUsage(0.25).setAvailableProcessors(4).build());
        BackendStatusService service = new BackendStatusService("backend-node", resources, runtime);

        var first = service.status();
        var second = service.status();

        assertThat(first.name()).isEqualTo("backend-node");
        assertThat(first.instanceId()).isEqualTo(second.instanceId());
        assertThat(first.startedAt()).isEqualTo(Instant.ofEpochMilli(1_700_000_000_000L));
        assertThat(first.uptimeMillis()).isEqualTo(5_000L);
        assertThat(second.uptimeMillis()).isEqualTo(6_000L);
        assertThat(second.sampledAt()).isAfterOrEqualTo(first.sampledAt());
        assertThat(first.resourceUsage().memoryUsedBytes()).isEqualTo(123);
        assertThat(first.resourceUsage().memoryMaxBytes()).isEqualTo(1024);
        assertThat(first.resourceUsage().heapUsedBytes()).isEqualTo(64);
        assertThat(first.resourceUsage().heapMaxBytes()).isEqualTo(512);
        assertThat(first.resourceUsage().cpuUsage()).isEqualTo(0.25);
        assertThat(first.resourceUsage().availableProcessors()).isEqualTo(4);
        assertThat(new BackendStatusService("backend-node", resources, runtime).status().instanceId()).isNotEqualTo(first.instanceId());
    }

    @Test
    void unknownMeasurementsRemainUnknownInsteadOfAppearingAsZero() {
        WorkerResourceMonitor resources = mock(WorkerResourceMonitor.class);
        RuntimeMXBean runtime = mock(RuntimeMXBean.class);
        when(resources.usage()).thenReturn(WorkerResourceUsage.newBuilder()
                .setMemoryUsedBytes(-1).setMemoryMaxBytes(-1).setHeapUsedBytes(-1).setHeapMaxBytes(-1)
                .setCpuUsage(-1).setAvailableProcessors(2).build());

        var status = new BackendStatusService("", resources, runtime).status();

        assertThat(status.name()).isEqualTo("backend");
        assertThat(status.resourceUsage().memoryUsedBytes()).isNull();
        assertThat(status.resourceUsage().memoryMaxBytes()).isNull();
        assertThat(status.resourceUsage().heapUsedBytes()).isNull();
        assertThat(status.resourceUsage().heapMaxBytes()).isNull();
        assertThat(status.resourceUsage().cpuUsage()).isNull();
    }
}
