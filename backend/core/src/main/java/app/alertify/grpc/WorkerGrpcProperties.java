package app.alertify.grpc;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Connection and periodic-discovery settings for the single worker DNS pool.
 */
@ConfigurationProperties("worker.grpc")
public record WorkerGrpcProperties(
    String host,
    int port,
    int maxInboundMessageBytes,
    Tls tls,
    Discovery discovery,
    Execution execution
) {

    @ConstructorBinding
    public WorkerGrpcProperties {
    }

    public WorkerGrpcProperties(String host, int port, Tls tls, Discovery discovery, Execution execution) {
        this(host, port, 134217728, tls, discovery, execution);
    }

    public record Tls(
        boolean enabled,
        String serverName,
        Path certificateChain,
        Path privateKey,
        Path serverCaCertificate
    ) {
    }

    public record Discovery(
        boolean enabled,
        Duration interval,
        Duration initialDelay,
        Duration healthTimeout
    ) {
    }

    public record Execution(
        Duration timeout,
        Path sourceRoot
    ) {
    }
}
