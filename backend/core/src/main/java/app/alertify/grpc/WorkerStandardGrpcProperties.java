package app.alertify.grpc;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection and startup-probe settings for the standard worker gRPC client.
 */
@ConfigurationProperties("worker-standard.grpc")
public record WorkerStandardGrpcProperties(
    String host,
    int port,
    StartupHealthCheck startupHealthCheck
) {

    public record StartupHealthCheck(
        boolean enabled,
        int attempts,
        Duration timeout,
        Duration delay
    ) {
    }
}
