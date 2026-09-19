package app.alertify.grpc.api;

/**
 * Resources consumed by a worker as reported by its runtime. Byte figures
 * and the CPU usage are {@code null} when the worker could not measure them.
 * {@code cpuUsage} is the fraction of the available processors in use.
 */
public record WorkerResourceUsageResponse(
    Long memoryUsedBytes,
    Long memoryMaxBytes,
    Long heapUsedBytes,
    Long heapMaxBytes,
    Double cpuUsage,
    int availableProcessors
) {
}
