package app.alertify.worker.codex;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("alertify.worker")
public record CodexWorkerProperties(
    String name,
    int grpcPort,
    int maxInboundMessageBytes,
    Duration shutdownGracePeriod,
    Duration refreshTokenLifetime,
    int maxConcurrentModelRequests,
    String defaultModel,
    String defaultReasoningEffort,
    String defaultReviewerModel,
    String defaultReviewerReasoningEffort,
    String defaultRefreshPolicy,
    Path stateFile,
    String stateKey,
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
