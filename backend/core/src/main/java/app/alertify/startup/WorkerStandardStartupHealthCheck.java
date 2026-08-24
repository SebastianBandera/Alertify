package app.alertify.startup;

import static io.grpc.health.v1.HealthCheckResponse.ServingStatus.SERVING;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import app.alertify.grpc.WorkerStandardGrpcClient;
import app.alertify.grpc.WorkerStandardGrpcProperties;

/**
 * Performs the temporary startup proof that the backend can reach the standard
 * worker through gRPC. Startup fails after the configured retries when the
 * worker does not report the standard SERVING status.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WorkerStandardStartupHealthCheck implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkerStandardStartupHealthCheck.class);

    private final WorkerStandardGrpcClient client;
    private final WorkerStandardGrpcProperties properties;

    public WorkerStandardStartupHealthCheck(WorkerStandardGrpcClient client, WorkerStandardGrpcProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        WorkerStandardGrpcProperties.StartupHealthCheck healthCheck = properties.startupHealthCheck();
        if (healthCheck == null || !healthCheck.enabled()) {
            LOGGER.info("Standard worker startup gRPC health check is disabled");
            return;
        }
        validate(healthCheck);

        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= healthCheck.attempts(); attempt++) {
            try {
                var status = client.checkHealth(healthCheck.timeout());
                if (status == SERVING) {
                    LOGGER.info("Standard worker gRPC health check succeeded at {}:{}", properties.host(), properties.port());
                    return;
                }
                lastFailure = new IllegalStateException("The standard worker reported gRPC health status " + status);
            } catch (RuntimeException exception) {
                lastFailure = exception;
            }

            if (attempt < healthCheck.attempts()) {
                LOGGER.warn("Standard worker gRPC health check attempt {}/{} failed; retrying", attempt, healthCheck.attempts());
                pause(healthCheck.delay());
            }
        }

        throw new IllegalStateException("The standard worker did not become healthy through gRPC after " + healthCheck.attempts() + " attempts", lastFailure);
    }

    private static void validate(WorkerStandardGrpcProperties.StartupHealthCheck healthCheck) {
        if (healthCheck.attempts() < 1)
            throw new IllegalStateException("worker-standard.grpc.startup-health-check.attempts must be positive");
        if (healthCheck.timeout() == null || healthCheck.timeout().isZero() || healthCheck.timeout().isNegative())
            throw new IllegalStateException("worker-standard.grpc.startup-health-check.timeout must be positive");
        if (healthCheck.delay() == null || healthCheck.delay().isNegative())
            throw new IllegalStateException("worker-standard.grpc.startup-health-check.delay must not be negative");
    }

    private static void pause(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("The standard worker gRPC health check was interrupted", exception);
        }
    }
}
