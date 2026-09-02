package app.alertify.grpc;

import java.util.Set;

import app.alertify.worker.contract.WorkerCapability;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;

/**
 * Contains the general health status and the independently advertised
 * capabilities returned by one direct worker probe.
 */
public record WorkerGrpcProbeResult(
    ServingStatus status,
    Set<WorkerCapability> capabilities
) {
}
