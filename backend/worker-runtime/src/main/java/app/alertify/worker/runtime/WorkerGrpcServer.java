package app.alertify.worker.runtime;

import static io.grpc.health.v1.HealthCheckResponse.ServingStatus.SERVING;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import app.alertify.worker.contract.WorkerCapability;
import io.grpc.Server;
import io.grpc.TlsServerCredentials;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.HealthStatusManager;

class WorkerGrpcServer implements SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkerGrpcServer.class);

    private final WorkerRuntimeProperties properties;
    private final AlertWorkerGrpcService workerService;
    private final WorkerInstanceIdentity instanceIdentity;
    private final HealthStatusManager healthStatusManager = new HealthStatusManager();

    private volatile boolean running;
    private Server server;

    WorkerGrpcServer(WorkerRuntimeProperties properties, AlertWorkerGrpcService workerService, WorkerInstanceIdentity instanceIdentity) {
        this.properties = properties;
        this.workerService = workerService;
        this.instanceIdentity = instanceIdentity;
    }

    @Override
    public synchronized void start() {
        if (running)
            return;

        validateProperties();
        try {
            healthStatusManager.setStatus("", SERVING);
            for (WorkerCapability capability : properties.capabilities())
                healthStatusManager.setStatus(capability.healthServiceName(), SERVING);

            server = NettyServerBuilder.forPort(properties.grpcPort(), serverCredentials())
                    .addService(healthStatusManager.getHealthService())
                    .addService(workerService)
                    .build()
                    .start();
            running = true;
            LOGGER.info("Worker gRPC server started: name={}, instanceId={}, port={}, capabilities={}, maxConcurrentAlerts={}", properties.name(), instanceIdentity.id(), server.getPort(), properties.capabilities(), properties.maxConcurrentAlerts());
        } catch (IOException exception) {
            healthStatusManager.enterTerminalState();
            throw new IllegalStateException("The worker gRPC server could not be started", exception);
        }
    }

    private io.grpc.ServerCredentials serverCredentials() {
        WorkerRuntimeProperties.Tls tls = properties.tls();
        if (tls == null || !tls.enabled())
            return io.grpc.InsecureServerCredentials.create();

        requireReadableFile(tls.certificateChain(), "alertify.worker.tls.certificate-chain");
        requireReadableFile(tls.privateKey(), "alertify.worker.tls.private-key");
        requireReadableFile(tls.clientCaCertificate(), "alertify.worker.tls.client-ca-certificate");
        try {
            return TlsServerCredentials.newBuilder()
                    .keyManager(tls.certificateChain().toFile(), tls.privateKey().toFile())
                    .trustManager(tls.clientCaCertificate().toFile())
                    .clientAuth(TlsServerCredentials.ClientAuth.REQUIRE)
                    .build();
        } catch (IOException exception) {
            throw new IllegalStateException("The worker gRPC mTLS credentials could not be loaded", exception);
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
            LOGGER.info("Worker gRPC server stopped: name={}", properties.name());
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
        Server current = server;
        if (current == null)
            throw new IllegalStateException("The worker gRPC server is not running");

        return current.getPort();
    }

    private void validateProperties() {
        if (properties.name() == null || properties.name().isBlank())
            throw new IllegalStateException("alertify.worker.name must not be blank");

        if (properties.grpcPort() < 0 || properties.grpcPort() > 65535)
            throw new IllegalStateException("alertify.worker.grpc-port must be between 0 and 65535");

        if (properties.shutdownGracePeriod() == null || properties.shutdownGracePeriod().isNegative())
            throw new IllegalStateException("alertify.worker.shutdown-grace-period must not be negative");

        if (properties.capabilities() == null || properties.capabilities().isEmpty())
            throw new IllegalStateException("alertify.worker.capabilities must not be empty");

        if (properties.compilerOutputDirectory() == null)
            throw new IllegalStateException("alertify.worker.compiler-output-directory must be configured");
    }

    private static void requireReadableFile(java.nio.file.Path path, String property) {
        if (path == null || !Files.isRegularFile(path) || !Files.isReadable(path))
            throw new IllegalStateException(property + " must reference a readable file");
    }
}
