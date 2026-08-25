package app.alertify.worker.playwright.grpc;

import static io.grpc.health.v1.HealthCheckResponse.ServingStatus.SERVING;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import app.alertify.worker.contract.WorkerCapability;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.HealthStatusManager;

/**
 * Owns the Playwright worker's native Netty gRPC server, publishes its
 * cumulative capabilities and integrates shutdown with Spring's lifecycle.
 */
@Component
public class WorkerPlaywrightGrpcServer implements SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkerPlaywrightGrpcServer.class);

    private final WorkerPlaywrightGrpcServerProperties properties;
    private final TemporaryGrpcHealthLoggingInterceptor healthLoggingInterceptor;
    private final HealthStatusManager healthStatusManager = new HealthStatusManager();

    private volatile boolean running;
    private Server server;

    public WorkerPlaywrightGrpcServer(WorkerPlaywrightGrpcServerProperties properties, TemporaryGrpcHealthLoggingInterceptor healthLoggingInterceptor) {
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
            server = NettyServerBuilder.forPort(properties.port())
                    .addService(
                            ServerInterceptors.intercept(
                                    healthStatusManager.getHealthService(),
                                    healthLoggingInterceptor
                            )
                    )
                    .build()
                    .start();
            running = true;
            LOGGER.info("Playwright worker gRPC server started on port {} with capabilities {}", server.getPort(), properties.capabilities());
        } catch (IOException exception) {
            healthStatusManager.enterTerminalState();
            throw new IllegalStateException("The Playwright worker gRPC server could not be started", exception);
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
            LOGGER.info("Playwright worker gRPC server stopped");
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
            throw new IllegalStateException("The Playwright worker gRPC server is not running");
        return currentServer.getPort();
    }

    private void validateProperties() {
        if (properties.port() < 0 || properties.port() > 65535)
            throw new IllegalStateException("worker-playwright.grpc.port must be between 0 and 65535");
        if (properties.shutdownGracePeriod() == null || properties.shutdownGracePeriod().isNegative())
            throw new IllegalStateException("worker-playwright.grpc.shutdown-grace-period must not be negative");
        if (properties.capabilities() == null || properties.capabilities().isEmpty())
            throw new IllegalStateException("worker-playwright.grpc.capabilities must not be empty");
    }
}
