package app.alertify.worker.playwright.grpc;

import java.time.Duration;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

import app.alertify.worker.contract.WorkerCapability;

/**
 * External configuration for the Playwright worker's native gRPC server and
 * cumulative capability declaration.
 */
@ConfigurationProperties("worker-playwright.grpc")
public record WorkerPlaywrightGrpcServerProperties(
    int port,
    Duration shutdownGracePeriod,
    Set<WorkerCapability> capabilities
) {
}
