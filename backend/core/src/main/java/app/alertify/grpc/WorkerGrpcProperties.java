package app.alertify.grpc;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection and periodic-discovery settings for the single worker DNS pool.
 */
@ConfigurationProperties("worker.grpc")
public record WorkerGrpcProperties(
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
