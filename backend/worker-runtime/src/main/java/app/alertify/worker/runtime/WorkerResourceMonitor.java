package app.alertify.worker.runtime;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.ToLongFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.management.OperatingSystemMXBean;

import app.alertify.worker.grpc.WorkerResourceUsage;

/**
 * Samples the memory and CPU consumed by the worker for the status endpoint.
 *
 * <p>Memory and processor limits come from the JDK's container-aware operating
 * system bean, so inside Docker they describe the container rather than the
 * host. CPU usage is derived from the cgroup CPU accounting whenever it is
 * readable, so subprocesses such as Playwright browsers count towards the
 * worker; otherwise it falls back to the load of the JVM process alone. Usage
 * is recomputed at most once per sampling window so the frequent status polls
 * do not produce noisy figures.</p>
 */
class WorkerResourceMonitor {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkerResourceMonitor.class);
    private static final Duration SAMPLE_WINDOW = Duration.ofSeconds(2);
    private static final List<CgroupCpuAccounting> CGROUP_CPU_ACCOUNTINGS = List.of(
            new CgroupCpuAccounting(Path.of("/sys/fs/cgroup/cpu.stat"), WorkerResourceMonitor::cgroupV2UsageNanos),
            new CgroupCpuAccounting(Path.of("/sys/fs/cgroup/cpu,cpuacct/cpuacct.usage"), WorkerResourceMonitor::cgroupV1UsageNanos),
            new CgroupCpuAccounting(Path.of("/sys/fs/cgroup/cpuacct/cpuacct.usage"), WorkerResourceMonitor::cgroupV1UsageNanos)
    );

    private final OperatingSystemMXBean operatingSystem = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private final Runtime runtime = Runtime.getRuntime();
    private final CgroupCpuAccounting cgroupCpuAccounting;
    private long lastCpuUsageNanos;
    private long lastSampledAtNanos;
    private double cpuUsage = -1;

    WorkerResourceMonitor() {
        cgroupCpuAccounting = CGROUP_CPU_ACCOUNTINGS.stream()
                .filter(accounting -> accounting.usageNanos().isPresent())
                .findFirst()
                .orElse(null);
        LOGGER.info("Worker resource monitoring initialized: cpuSource={}, availableProcessors={}, memoryMaxBytes={}", cgroupCpuAccounting == null ? "jvm-process" : cgroupCpuAccounting.file(), runtime.availableProcessors(), operatingSystem.getTotalMemorySize());
        sampleCpu();
    }

    synchronized WorkerResourceUsage usage() {
        sampleCpu();
        long totalMemory = operatingSystem.getTotalMemorySize();
        long freeMemory = operatingSystem.getFreeMemorySize();
        long heapMax = runtime.maxMemory();
        return WorkerResourceUsage.newBuilder()
                .setMemoryUsedBytes(totalMemory > 0 && freeMemory >= 0 ? Math.max(0, totalMemory - freeMemory) : -1)
                .setMemoryMaxBytes(totalMemory > 0 ? totalMemory : -1)
                .setHeapUsedBytes(runtime.totalMemory() - runtime.freeMemory())
                .setHeapMaxBytes(heapMax == Long.MAX_VALUE ? -1 : heapMax)
                .setCpuUsage(cpuUsage)
                .setAvailableProcessors(runtime.availableProcessors())
                .build();
    }

    private void sampleCpu() {
        long now = System.nanoTime();
        if (lastSampledAtNanos != 0 && now - lastSampledAtNanos < SAMPLE_WINDOW.toNanos())
            return;

        if (cgroupCpuAccounting == null) {
            lastSampledAtNanos = now;
            cpuUsage = clamp(operatingSystem.getProcessCpuLoad());
            return;
        }

        Optional<Long> usageNanos = cgroupCpuAccounting.usageNanos();
        if (usageNanos.isEmpty())
            return;

        if (lastSampledAtNanos != 0) {
            double elapsed = (double) (now - lastSampledAtNanos) * runtime.availableProcessors();
            cpuUsage = clamp((usageNanos.get() - lastCpuUsageNanos) / elapsed);
        }
        lastCpuUsageNanos = usageNanos.get();
        lastSampledAtNanos = now;
    }

    private static double clamp(double value) {
        return value < 0 ? -1 : Math.min(1, value);
    }

    private static long cgroupV2UsageNanos(String content) {
        for (String line : content.split("\n")) {
            if (line.startsWith("usage_usec "))
                return Long.parseLong(line.substring("usage_usec ".length()).trim()) * 1_000;
        }
        throw new IllegalStateException("cpu.stat has no usage_usec entry");
    }

    private static long cgroupV1UsageNanos(String content) {
        return Long.parseLong(content.trim());
    }

    private record CgroupCpuAccounting(Path file, ToLongFunction<String> parser) {

        Optional<Long> usageNanos() {
            try {
                return Optional.of(parser.applyAsLong(Files.readString(file)));
            } catch (IOException | RuntimeException exception) {
                return Optional.empty();
            }
        }
    }
}
