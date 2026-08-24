package app.alertify.worker.standard.grpc;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * External configuration for the standard worker's native gRPC server.
 */
@ConfigurationProperties("worker-standard.grpc")
public record WorkerStandardGrpcServerProperties(
    int port,
    Duration shutdownGracePeriod
) {
}
