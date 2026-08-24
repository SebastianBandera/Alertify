package app.alertify.grpc;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.health.v1.HealthGrpc;

/**
 * Provides backend-side operations against the standard worker's gRPC API.
 * The initial implementation uses the standard health protocol as the first
 * end-to-end connectivity check.
 */
@Component
public class WorkerStandardGrpcClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkerStandardGrpcClient.class);

    private final HealthGrpc.HealthBlockingStub healthStub;

    public WorkerStandardGrpcClient(HealthGrpc.HealthBlockingStub healthStub) {
        this.healthStub = healthStub;
    }

    public ServingStatus checkHealth(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative())
            throw new IllegalArgumentException("timeout must be positive");

        LOGGER.info("Sending temporary gRPC health request to worker-standard");
        try {
            ServingStatus status = healthStub
                    .withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .check(HealthCheckRequest.getDefaultInstance())
                    .getStatus();
            LOGGER.info("Received temporary gRPC health response from worker-standard: {}", status);
            return status;
        } catch (StatusRuntimeException exception) {
            LOGGER.warn(
                    "Temporary gRPC health request to worker-standard failed: code={}, description={}",
                    exception.getStatus().getCode(),
                    exception.getStatus().getDescription()
            );
            throw exception;
        }
    }
}
