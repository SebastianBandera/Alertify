package app.alertify.worker.playwright.grpc;

import static io.grpc.health.v1.HealthCheckResponse.ServingStatus.SERVING;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.Test;

import app.alertify.worker.contract.WorkerCapability;
import io.grpc.ManagedChannel;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;

class WorkerPlaywrightGrpcServerTest {

    @Test
    void exposesPlaywrightCapabilityThroughGrpcHealthService() {
        WorkerPlaywrightGrpcServerProperties.Tls tls = new WorkerPlaywrightGrpcServerProperties.Tls(false, null, null, null);
        WorkerPlaywrightGrpcServer server = new WorkerPlaywrightGrpcServer(new WorkerPlaywrightGrpcServerProperties(0, Duration.ofSeconds(1), Set.of(WorkerCapability.PLAYWRIGHT), tls), new TemporaryGrpcHealthLoggingInterceptor());
        server.start();
        ManagedChannel channel = NettyChannelBuilder.forAddress("127.0.0.1", server.port()).usePlaintext().build();
        try {
            var response = HealthGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(2, SECONDS)
                    .check(
                            HealthCheckRequest.newBuilder()
                                    .setService(WorkerCapability.PLAYWRIGHT.healthServiceName())
                                    .build()
                    );
            assertThat(response.getStatus()).isEqualTo(SERVING);
        } finally {
            channel.shutdownNow();
            server.stop();
        }
    }
}
