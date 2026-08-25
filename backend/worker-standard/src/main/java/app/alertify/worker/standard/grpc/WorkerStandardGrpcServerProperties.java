package app.alertify.worker.standard.grpc;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

import app.alertify.worker.contract.WorkerCapability;

/**
 * External configuration for the standard worker's native gRPC server.
 */
@ConfigurationProperties("worker-standard.grpc")
public record WorkerStandardGrpcServerProperties(
    int port,
    Duration shutdownGracePeriod,
    Set<WorkerCapability> capabilities,
    Tls tls
) {

    public record Tls(
        boolean enabled,
        Path certificateChain,
        Path privateKey,
        Path clientCaCertificate
    ) {
    }
}
