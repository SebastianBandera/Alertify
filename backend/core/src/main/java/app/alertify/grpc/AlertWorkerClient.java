package app.alertify.grpc;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.google.protobuf.Empty;

import app.alertify.grpc.discovery.WorkerEndpoint;
import app.alertify.worker.grpc.AlertWorkerServiceGrpc;
import app.alertify.worker.grpc.ExecuteAlertRequest;
import app.alertify.worker.grpc.ExecuteAlertResponse;
import app.alertify.worker.grpc.SynchronizeTemplateRequest;
import app.alertify.worker.grpc.SynchronizeTemplateResponse;
import app.alertify.worker.grpc.WorkerStatusResponse;
import io.grpc.ManagedChannel;

@Component
public class AlertWorkerClient {

    private final WorkerGrpcChannelFactory channelFactory;

    public AlertWorkerClient(WorkerGrpcChannelFactory channelFactory) {
        this.channelFactory = channelFactory;
    }

    public WorkerStatusResponse status(WorkerEndpoint endpoint, Duration timeout) {
        return call(endpoint, timeout, stub -> stub.getStatus(Empty.getDefaultInstance()));
    }

    public ExecuteAlertResponse execute(WorkerEndpoint endpoint, ExecuteAlertRequest request, Duration timeout) {
        return call(endpoint, timeout, stub -> stub.executeAlert(request));
    }

    public SynchronizeTemplateResponse synchronize(WorkerEndpoint endpoint, SynchronizeTemplateRequest request, Duration timeout) {
        return call(endpoint, timeout, stub -> stub.synchronizeTemplate(request));
    }

    private <T> T call(WorkerEndpoint endpoint, Duration timeout, GrpcCall<T> call) {
        if (timeout == null || timeout.isZero() || timeout.isNegative())
            throw new IllegalArgumentException("gRPC timeout must be positive");
        ManagedChannel channel = channelFactory.create(endpoint);
        try {
            AlertWorkerServiceGrpc.AlertWorkerServiceBlockingStub stub = AlertWorkerServiceGrpc.newBlockingStub(channel)
                            .withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return call.invoke(stub);
        } finally {
            channel.shutdownNow();
            try {
                channel.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @FunctionalInterface
    private interface GrpcCall<T> {

        T invoke(AlertWorkerServiceGrpc.AlertWorkerServiceBlockingStub stub);
    }
}
