package app.alertify.grpc.discovery;

import java.util.Set;

import app.alertify.worker.contract.WorkerCapability;

/**
 * Describes one healthy worker IP and every cumulative capability advertised
 * by that node during its most recent probe.
 */
public record AvailableWorker(
    String ipAddress,
    int port,
    Set<WorkerCapability> capabilities
) {
}
