package app.alertify.worker.runtime;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

import app.alertify.worker.contract.WorkerCapability;

@ConfigurationProperties("alertify.worker")
public record WorkerRuntimeProperties(
    String name,
    int grpcPort,
    Duration shutdownGracePeriod,
    Set<WorkerCapability> capabilities,
    int maxConcurrentAlerts,
    Path compilerOutputDirectory,
    Path compilerClasspathDirectory,
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
