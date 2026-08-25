package app.alertify.grpc;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection and periodic-discovery settings for standard worker gRPC nodes.
 */
@ConfigurationProperties("worker-standard.grpc")
public record WorkerStandardGrpcProperties(
    String host,
    int port,
    Discovery discovery
) {

    public record Discovery(
        boolean enabled,
        Duration interval,
        Duration initialDelay,
        Duration healthTimeout
    ) {
    }
}
