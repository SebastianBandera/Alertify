package app.alertify.grpc;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import app.alertify.grpc.discovery.WorkerEndpoint;
import app.alertify.worker.contract.WorkerCapability;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.health.v1.HealthGrpc;

/**
 * Performs one mutually authenticated health inspection against a discovered
 * worker IP and collects every capability that the same node advertises.
 */
@Component
public class WorkerGrpcHealthProbe {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkerGrpcHealthProbe.class);

    private final WorkerGrpcChannelFactory channelFactory;

    public WorkerGrpcHealthProbe(WorkerGrpcChannelFactory channelFactory) {
        this.channelFactory = channelFactory;
    }

    public WorkerGrpcProbeResult inspect(WorkerEndpoint endpoint, Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative())
            throw new IllegalArgumentException("timeout must be positive");

        ManagedChannel channel = channelFactory.create(endpoint);
        LOGGER.debug("Sending gRPC health and capability requests to worker at {}", endpoint);
        try {
            HealthGrpc.HealthBlockingStub stub = HealthGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS);
            ServingStatus status = stub
                    .check(HealthCheckRequest.getDefaultInstance())
                    .getStatus();
            Set<WorkerCapability> capabilities = readCapabilities(stub);
            LOGGER.debug("Received gRPC worker response from {}: status={}, capabilities={}", endpoint, status, capabilities);
            return new WorkerGrpcProbeResult(status, capabilities);
        } catch (StatusRuntimeException exception) {
            LOGGER.warn("gRPC worker request to {} failed: code={}, description={}", endpoint, exception.getStatus().getCode(), exception.getStatus().getDescription());
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

    private static Set<WorkerCapability> readCapabilities(HealthGrpc.HealthBlockingStub stub) {
        Set<WorkerCapability> capabilities = EnumSet.noneOf(WorkerCapability.class);
        for (WorkerCapability capability : WorkerCapability.values()) {
            try {
                ServingStatus status = stub.check(
                        HealthCheckRequest.newBuilder()
                                .setService(capability.healthServiceName())
                                .build()
                ).getStatus();
                if (status == ServingStatus.SERVING)
                    capabilities.add(capability);
            } catch (StatusRuntimeException exception) {
                if (exception.getStatus().getCode() != Status.Code.NOT_FOUND)
                    throw exception;
            }
        }
        return Set.copyOf(capabilities);
    }
}
