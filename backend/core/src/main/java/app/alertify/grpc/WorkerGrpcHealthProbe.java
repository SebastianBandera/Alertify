package app.alertify.grpc;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import app.alertify.grpc.discovery.WorkerEndpoint;
import app.alertify.worker.contract.WorkerCapability;
import io.grpc.ChannelCredentials;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.TlsChannelCredentials;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;

/**
 * Performs one mutually authenticated health inspection against a discovered
 * worker IP and collects every capability that the same node advertises.
 */
@Component
public class WorkerGrpcHealthProbe {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkerGrpcHealthProbe.class);

    private final WorkerGrpcProperties properties;

    public WorkerGrpcHealthProbe(WorkerGrpcProperties properties) {
        this.properties = properties;
    }

    public WorkerGrpcProbeResult inspect(WorkerEndpoint endpoint, Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative())
            throw new IllegalArgumentException("timeout must be positive");

        ManagedChannel channel = newChannel(endpoint);
        LOGGER.info("Sending gRPC health and capability requests to worker at {}", endpoint);
        try {
            HealthGrpc.HealthBlockingStub stub = HealthGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS);
            ServingStatus status = stub
                    .check(HealthCheckRequest.getDefaultInstance())
                    .getStatus();
            Set<WorkerCapability> capabilities = readCapabilities(stub);
            LOGGER.info("Received gRPC worker response from {}: status={}, capabilities={}", endpoint, status, capabilities);
            return new WorkerGrpcProbeResult(status, capabilities);
        } catch (StatusRuntimeException exception) {
            LOGGER.warn(
                    "gRPC worker request to {} failed: code={}, description={}",
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

    private ManagedChannel newChannel(WorkerEndpoint endpoint) {
        WorkerGrpcProperties.Tls tls = properties.tls();
        NettyChannelBuilder builder = NettyChannelBuilder.forAddress(endpoint.ipAddress(), endpoint.port(), channelCredentials(tls));

        if (tls != null && tls.enabled())
            builder.overrideAuthority(tls.serverName());

        return builder.build();
    }

    private static ChannelCredentials channelCredentials(WorkerGrpcProperties.Tls tls) {
        if (tls == null || !tls.enabled())
            return InsecureChannelCredentials.create();

        validateTls(tls);
        try {
            return TlsChannelCredentials.newBuilder()
                    .trustManager(tls.serverCaCertificate().toFile())
                    .keyManager(tls.certificateChain().toFile(), tls.privateKey().toFile())
                    .build();
        } catch (IOException exception) {
            throw new IllegalStateException("The backend gRPC mTLS credentials could not be loaded", exception);
        }
    }

    private static void validateTls(WorkerGrpcProperties.Tls tls) {
        if (tls.serverName() == null || tls.serverName().isBlank())
            throw new IllegalStateException("worker.grpc.tls.server-name must not be blank");
        requireReadableFile(tls.certificateChain(), "worker.grpc.tls.certificate-chain");
        requireReadableFile(tls.privateKey(), "worker.grpc.tls.private-key");
        requireReadableFile(tls.serverCaCertificate(), "worker.grpc.tls.server-ca-certificate");
    }

    private static void requireReadableFile(java.nio.file.Path path, String property) {
        if (path == null || !Files.isRegularFile(path) || !Files.isReadable(path))
            throw new IllegalStateException(property + " must reference a readable file");
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
