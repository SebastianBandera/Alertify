package app.alertify.worker.standard.grpc;

import static io.grpc.health.v1.HealthCheckResponse.ServingStatus.SERVING;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.grpc.ManagedChannel;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;

class WorkerStandardGrpcServerTest {

    @Test
    void exposesStandardGrpcHealthService() {
        WorkerStandardGrpcServer server = new WorkerStandardGrpcServer(new WorkerStandardGrpcServerProperties(0, Duration.ofSeconds(1)));
        server.start();
        ManagedChannel channel = NettyChannelBuilder.forAddress("127.0.0.1", server.port()).usePlaintext().build();
        try {
            var response = HealthGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(2, SECONDS)
                    .check(HealthCheckRequest.getDefaultInstance());
            assertThat(response.getStatus()).isEqualTo(SERVING);
        } finally {
            channel.shutdownNow();
            server.stop();
        }
    }
}
