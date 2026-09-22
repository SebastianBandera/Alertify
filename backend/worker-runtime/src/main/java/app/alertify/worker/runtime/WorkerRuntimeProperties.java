package app.alertify.worker.runtime;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import app.alertify.worker.contract.WorkerCapability;

@ConfigurationProperties("alertify.worker")
public record WorkerRuntimeProperties(
    String name,
    int grpcPort,
    int maxInboundMessageBytes,
    Duration shutdownGracePeriod,
    Set<WorkerCapability> capabilities,
    int maxConcurrentAlerts,
    Path compilerOutputDirectory,
    Path compilerClasspathDirectory,
    ArtifactStorage artifactStorage,
    Tls tls
) {
    @ConstructorBinding
    public WorkerRuntimeProperties {
        if (artifactStorage == null)
            artifactStorage = new ArtifactStorage(compilerOutputDirectory.resolveSibling("artifacts"),
                    10L * 1024 * 1024 * 1024, 20L * 1024 * 1024 * 1024, Duration.ofMinutes(5));
    }

    public WorkerRuntimeProperties(String name, int grpcPort, int maxInboundMessageBytes, Duration shutdownGracePeriod,
            Set<WorkerCapability> capabilities, int maxConcurrentAlerts, Path compilerOutputDirectory,
            Path compilerClasspathDirectory, Tls tls) {
        this(name, grpcPort, maxInboundMessageBytes, shutdownGracePeriod, capabilities, maxConcurrentAlerts,
                compilerOutputDirectory, compilerClasspathDirectory,
                new ArtifactStorage(compilerOutputDirectory.resolveSibling("artifacts"), 10L * 1024 * 1024 * 1024,
                        20L * 1024 * 1024 * 1024, Duration.ofMinutes(5)), tls);
    }

    public record ArtifactStorage(
        Path directory,
        long maxArtifactBytes,
        long maxTotalBytes,
        Duration sweepInterval
    ) {
    }

    public record Tls(
        boolean enabled,
        Path certificateChain,
        Path privateKey,
        Path clientCaCertificate
    ) {
    }
}
