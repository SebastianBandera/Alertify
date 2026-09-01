package app.alertify.grpc;

import java.io.IOException;
import java.nio.file.Files;

import org.springframework.stereotype.Component;

import app.alertify.grpc.discovery.WorkerEndpoint;
import io.grpc.ChannelCredentials;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.TlsChannelCredentials;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;

@Component
public class WorkerGrpcChannelFactory {

    private final WorkerGrpcProperties properties;

    public WorkerGrpcChannelFactory(WorkerGrpcProperties properties) {
        this.properties = properties;
    }

    public ManagedChannel create(WorkerEndpoint endpoint) {
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
}
