package app.alertify.worker.standard.grpc;

import static io.grpc.health.v1.HealthCheckResponse.ServingStatus.SERVING;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import app.alertify.worker.contract.WorkerCapability;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.TlsServerCredentials;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.HealthStatusManager;

/**
 * Owns the worker's native Netty gRPC server, requires a backend certificate
 * through mTLS and integrates shutdown with the Spring application lifecycle.
 */
@Component
public class WorkerStandardGrpcServer implements SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkerStandardGrpcServer.class);

    private final WorkerStandardGrpcServerProperties properties;
    private final TemporaryGrpcHealthLoggingInterceptor healthLoggingInterceptor;
    private final HealthStatusManager healthStatusManager = new HealthStatusManager();

    private volatile boolean running;
    private Server server;

    public WorkerStandardGrpcServer(WorkerStandardGrpcServerProperties properties, TemporaryGrpcHealthLoggingInterceptor healthLoggingInterceptor) {
        this.properties = properties;
        this.healthLoggingInterceptor = healthLoggingInterceptor;
    }

    @Override
    public synchronized void start() {
        if (running)
            return;
        validateProperties();
        try {
            healthStatusManager.setStatus("", SERVING);
            for (WorkerCapability capability : properties.capabilities()) {
                healthStatusManager.setStatus(capability.healthServiceName(), SERVING);
            }
            NettyServerBuilder serverBuilder = NettyServerBuilder.forPort(properties.port(), serverCredentials());
            server = serverBuilder
                    .addService(
                            ServerInterceptors.intercept(
                                    healthStatusManager.getHealthService(),
                                    healthLoggingInterceptor
                            )
                    )
                    .build()
                    .start();
            running = true;
            LOGGER.info("Standard worker gRPC server started on port {} with capabilities {}", server.getPort(), properties.capabilities());
        } catch (IOException exception) {
            healthStatusManager.enterTerminalState();
            throw new IllegalStateException("The standard worker gRPC server could not be started", exception);
        }
    }

    private io.grpc.ServerCredentials serverCredentials() {
        WorkerStandardGrpcServerProperties.Tls tls = properties.tls();
        if (tls == null || !tls.enabled())
            return io.grpc.InsecureServerCredentials.create();

        requireReadableFile(tls.certificateChain(), "worker-standard.grpc.tls.certificate-chain");
        requireReadableFile(tls.privateKey(), "worker-standard.grpc.tls.private-key");
        requireReadableFile(tls.clientCaCertificate(), "worker-standard.grpc.tls.client-ca-certificate");
        try {
            return TlsServerCredentials.newBuilder()
                    .keyManager(tls.certificateChain().toFile(), tls.privateKey().toFile())
                    .trustManager(tls.clientCaCertificate().toFile())
                    .clientAuth(TlsServerCredentials.ClientAuth.REQUIRE)
                    .build();
        } catch (IOException exception) {
            throw new IllegalStateException("The standard worker gRPC mTLS credentials could not be loaded", exception);
        }
    }

    private static void requireReadableFile(java.nio.file.Path path, String property) {
        if (path == null || !Files.isRegularFile(path) || !Files.isReadable(path))
            throw new IllegalStateException(property + " must reference a readable file");
    }

    @Override
    public synchronized void stop() {
        if (!running)
            return;
        healthStatusManager.enterTerminalState();
        server.shutdown();
        try {
            Duration gracePeriod = properties.shutdownGracePeriod();
            if (!server.awaitTermination(gracePeriod.toMillis(), TimeUnit.MILLISECONDS)) {
                server.shutdownNow();
                server.awaitTermination(gracePeriod.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            server.shutdownNow();
        } finally {
            running = false;
            server = null;
            LOGGER.info("Standard worker gRPC server stopped");
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    int port() {
        Server currentServer = server;
        if (currentServer == null)
            throw new IllegalStateException("The standard worker gRPC server is not running");
        return currentServer.getPort();
    }

    private void validateProperties() {
        if (properties.port() < 0 || properties.port() > 65535)
            throw new IllegalStateException("worker-standard.grpc.port must be between 0 and 65535");
        if (properties.shutdownGracePeriod() == null || properties.shutdownGracePeriod().isNegative())
            throw new IllegalStateException("worker-standard.grpc.shutdown-grace-period must not be negative");
        if (properties.capabilities() == null || properties.capabilities().isEmpty())
            throw new IllegalStateException("worker-standard.grpc.capabilities must not be empty");
    }
}
