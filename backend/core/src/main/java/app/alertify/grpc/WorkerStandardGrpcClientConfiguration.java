package app.alertify.grpc;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.grpc.ManagedChannel;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;

/**
 * Creates the managed gRPC channel and health stub used by the backend to
 * communicate with the standard worker.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkerStandardGrpcProperties.class)
class WorkerStandardGrpcClientConfiguration {

    @Bean(destroyMethod = "shutdownNow")
    ManagedChannel workerStandardGrpcChannel(WorkerStandardGrpcProperties properties) {
        String host = properties.host();
        if (host == null || host.isBlank())
            throw new IllegalStateException("worker-standard.grpc.host must not be blank");
        if (properties.port() < 1 || properties.port() > 65535)
            throw new IllegalStateException("worker-standard.grpc.port must be between 1 and 65535");
        return NettyChannelBuilder.forAddress(host, properties.port())
                .usePlaintext()
                .build();
    }

    @Bean
    HealthGrpc.HealthBlockingStub workerStandardHealthStub(ManagedChannel workerStandardGrpcChannel) {
        return HealthGrpc.newBlockingStub(workerStandardGrpcChannel);
    }
}
