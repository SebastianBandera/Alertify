package app.alertify.worker.standard.grpc;

import static io.grpc.health.v1.HealthCheckResponse.ServingStatus.SERVING;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import io.grpc.Server;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;

/**
 * Owns the worker's native Netty gRPC server and integrates its startup and
 * graceful shutdown with the Spring application lifecycle.
 */
@Component
public class WorkerStandardGrpcServer implements SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkerStandardGrpcServer.class);

    private final WorkerStandardGrpcServerProperties properties;
    private final HealthStatusManager healthStatusManager = new HealthStatusManager();

    private volatile boolean running;
    private Server server;

    public WorkerStandardGrpcServer(WorkerStandardGrpcServerProperties properties) {
        this.properties = properties;
    }

    @Override
    public synchronized void start() {
        if (running)
            return;
        validateProperties();
        try {
            healthStatusManager.setStatus("", SERVING);
            server = NettyServerBuilder.forPort(properties.port())
                    .addService(healthStatusManager.getHealthService())
                    .build()
                    .start();
            running = true;
            LOGGER.info("Standard worker gRPC server started on port {}", server.getPort());
        } catch (IOException exception) {
            healthStatusManager.enterTerminalState();
            throw new IllegalStateException("The standard worker gRPC server could not be started", exception);
        }
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
    }
}
