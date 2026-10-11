package app.alertify.worker.codex;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.google.protobuf.Empty;
import com.google.protobuf.Timestamp;

import app.alertify.worker.contract.WorkerResourceMonitor;
import app.alertify.worker.grpc.AlertWorkerServiceGrpc;
import app.alertify.worker.grpc.WorkerStatusResponse;
import io.grpc.stub.StreamObserver;

@Component
class CodexWorkerStatusGrpcService extends AlertWorkerServiceGrpc.AlertWorkerServiceImplBase {

    private final CodexWorkerProperties properties;
    private final WorkerResourceMonitor resourceMonitor = new WorkerResourceMonitor();
    private final String instanceId = UUID.randomUUID().toString();
    private final Instant startedAt = Instant.now();

    CodexWorkerStatusGrpcService(CodexWorkerProperties properties) {
        this.properties = properties;
    }

    @Override
    public void getStatus(Empty request, StreamObserver<WorkerStatusResponse> observer) {
        observer.onNext(WorkerStatusResponse.newBuilder()
                .setWorkerName(properties.name()).setWorkerInstanceId(instanceId)
                .addCapabilities("AI").addCapabilities("CODEX")
                .setWorkerStartedAt(timestamp(startedAt))
                .setResourceUsage(resourceMonitor.usage())
                .build());
        observer.onCompleted();
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.newBuilder().setSeconds(value.getEpochSecond()).setNanos(value.getNano()).build();
    }
}
