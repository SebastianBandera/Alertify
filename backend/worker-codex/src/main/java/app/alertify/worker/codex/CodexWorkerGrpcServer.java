package app.alertify.worker.codex;

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
import io.grpc.InsecureServerCredentials;
import io.grpc.Server;
import io.grpc.ServerCredentials;
import io.grpc.TlsServerCredentials;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.HealthStatusManager;

@Component
class CodexWorkerGrpcServer implements SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(CodexWorkerGrpcServer.class);

    private final CodexWorkerProperties properties;
    private final AiWorkerGrpcService aiService;
    private final CodexWorkerStatusGrpcService statusService;
    private final HealthStatusManager health = new HealthStatusManager();
    private volatile boolean running;
    private Server server;

    CodexWorkerGrpcServer(CodexWorkerProperties properties, AiWorkerGrpcService aiService, CodexWorkerStatusGrpcService statusService) {
        this.properties = properties;
        this.aiService = aiService;
        this.statusService = statusService;
    }

    @Override
    public synchronized void start() {
        if (running)
            return;

        validate();
        try {
            health.setStatus("", SERVING);
            health.setStatus(WorkerCapability.AI.healthServiceName(), SERVING);
            health.setStatus(WorkerCapability.CODEX.healthServiceName(), SERVING);
            server = NettyServerBuilder.forPort(properties.grpcPort(), credentials())
                    .maxInboundMessageSize(properties.maxInboundMessageBytes())
                    .addService(health.getHealthService()).addService(statusService).addService(aiService)
                    .build().start();
            running = true;
            LOGGER.info("Codex worker gRPC server started: name={}, port={}, capabilities=[AI, CODEX]", properties.name(), server.getPort());
        } catch (IOException exception) {
            health.enterTerminalState();
            throw new IllegalStateException("The Codex worker gRPC server could not be started", exception);
        }
    }

    @Override
    public synchronized void stop() {
        if (!running)
            return;

        health.enterTerminalState();
        server.shutdown();
        try {
            Duration grace = properties.shutdownGracePeriod();
            if (!server.awaitTermination(grace.toMillis(), TimeUnit.MILLISECONDS))
                server.shutdownNow();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            server.shutdownNow();
        } finally {
            running = false;
            server = null;
            LOGGER.info("Codex worker gRPC server stopped: name={}", properties.name());
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

    private ServerCredentials credentials() {
        CodexWorkerProperties.Tls tls = properties.tls();
        if (tls == null || !tls.enabled())
            return InsecureServerCredentials.create();
        requireFile(tls.certificateChain(), "certificate chain");
        requireFile(tls.privateKey(), "private key");
        requireFile(tls.clientCaCertificate(), "client CA certificate");
        try {
            return TlsServerCredentials.newBuilder().keyManager(tls.certificateChain().toFile(), tls.privateKey().toFile())
                    .trustManager(tls.clientCaCertificate().toFile()).clientAuth(TlsServerCredentials.ClientAuth.REQUIRE).build();
        } catch (IOException exception) {
            throw new IllegalStateException("The Codex worker mTLS credentials could not be loaded", exception);
        }
    }

    private void validate() {
        if (properties.name() == null || properties.name().isBlank())
            throw new IllegalStateException("alertify.worker.name must not be blank");
        if (properties.grpcPort() < 0 || properties.grpcPort() > 65535)
            throw new IllegalStateException("alertify.worker.grpc-port must be between 0 and 65535");
        if (properties.stateFile() == null)
            throw new IllegalStateException("alertify.worker.state-file must be configured");
    }

    private static void requireFile(java.nio.file.Path path, String description) {
        if (path == null || !Files.isRegularFile(path) || !Files.isReadable(path))
            throw new IllegalStateException("The worker " + description + " must reference a readable file");
    }
}
