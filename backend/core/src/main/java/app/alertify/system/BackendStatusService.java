package app.alertify.system;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.time.Instant;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import app.alertify.grpc.api.WorkerResourceUsageResponse;
import app.alertify.system.api.BackendStatusResponse;
import app.alertify.worker.contract.WorkerResourceMonitor;
import app.alertify.worker.grpc.WorkerResourceUsage;

@Service
public class BackendStatusService {

    private final String name;
    private final UUID instanceId = UUID.randomUUID();
    private final WorkerResourceMonitor resources;
    private final RuntimeMXBean runtime;

    @Autowired
    public BackendStatusService(@Value("${HOSTNAME:${spring.application.name:backend}}") String name) {
        this(name, new WorkerResourceMonitor(), ManagementFactory.getRuntimeMXBean());
    }

    BackendStatusService(String name, WorkerResourceMonitor resources, RuntimeMXBean runtime) {
        this.name = name == null || name.isBlank() ? "backend" : name;
        this.resources = resources;
        this.runtime = runtime;
    }

    public BackendStatusResponse status() {
        WorkerResourceUsage usage = resources.usage();
        return new BackendStatusResponse(name, instanceId, Instant.ofEpochMilli(runtime.getStartTime()), Math.max(0, runtime.getUptime()), Instant.now(),
                new WorkerResourceUsageResponse(measured(usage.getMemoryUsedBytes()), measured(usage.getMemoryMaxBytes()),
                        measured(usage.getHeapUsedBytes()), measured(usage.getHeapMaxBytes()),
                        usage.getCpuUsage() < 0 || !Double.isFinite(usage.getCpuUsage()) ? null : usage.getCpuUsage(), usage.getAvailableProcessors()));
    }

    private static Long measured(long value) {
        return value < 0 ? null : value;
    }
}
