package app.alertify.grpc;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

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

    private final HealthGrpc.HealthBlockingStub healthStub;

    public WorkerStandardGrpcClient(HealthGrpc.HealthBlockingStub healthStub) {
        this.healthStub = healthStub;
    }

    public ServingStatus checkHealth(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative())
            throw new IllegalArgumentException("timeout must be positive");
        return healthStub
                .withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .check(HealthCheckRequest.getDefaultInstance())
                .getStatus();
    }
}
