package app.alertify.grpc;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import app.alertify.grpc.discovery.WorkerStandardEndpoint;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;

/**
 * Performs one direct standard gRPC health request against a discovered worker
 * IP address without passing through the shared DNS name.
 */
@Component
public class WorkerStandardGrpcHealthProbe {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkerStandardGrpcHealthProbe.class);

    public ServingStatus check(WorkerStandardEndpoint endpoint, Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative())
            throw new IllegalArgumentException("timeout must be positive");

        ManagedChannel channel = NettyChannelBuilder.forAddress(endpoint.ipAddress(), endpoint.port())
                .usePlaintext()
                .build();
        LOGGER.info("Sending gRPC health request to standard worker at {}", endpoint);
        try {
            ServingStatus status = HealthGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .check(HealthCheckRequest.getDefaultInstance())
                    .getStatus();
            LOGGER.info("Received gRPC health response from standard worker at {}: {}", endpoint, status);
            return status;
        } catch (StatusRuntimeException exception) {
            LOGGER.warn(
                    "gRPC health request to standard worker at {} failed: code={}, description={}",
                    endpoint,
                    exception.getStatus().getCode(),
                    exception.getStatus().getDescription()
            );
            throw exception;
        } finally {
            channel.shutdownNow();
            try {
                channel.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
