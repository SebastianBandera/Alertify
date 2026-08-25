package app.alertify.worker.standard.grpc;

import static io.grpc.health.v1.HealthCheckResponse.ServingStatus.SERVING;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.Test;

import app.alertify.worker.contract.WorkerCapability;
import io.grpc.ManagedChannel;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;

class WorkerStandardGrpcServerTest {

    @Test
    void exposesHealthForEveryConfiguredCapability() {
        WorkerStandardGrpcServer server = new WorkerStandardGrpcServer(new WorkerStandardGrpcServerProperties(0, Duration.ofSeconds(1), Set.of(WorkerCapability.STANDARD, WorkerCapability.PLAYWRIGHT)), new TemporaryGrpcHealthLoggingInterceptor());
        server.start();
        ManagedChannel channel = NettyChannelBuilder.forAddress("127.0.0.1", server.port()).usePlaintext().build();
        try {
            assertThat(check(channel, "").getStatus()).isEqualTo(SERVING);
            assertThat(check(channel, WorkerCapability.STANDARD.healthServiceName()).getStatus()).isEqualTo(SERVING);
            assertThat(check(channel, WorkerCapability.PLAYWRIGHT.healthServiceName()).getStatus()).isEqualTo(SERVING);
        } finally {
            channel.shutdownNow();
            server.stop();
        }
    }

    private static HealthCheckResponse check(ManagedChannel channel, String service) {
        return HealthGrpc.newBlockingStub(channel)
                .withDeadlineAfter(2, SECONDS)
                .check(HealthCheckRequest.newBuilder().setService(service).build());
    }
}
