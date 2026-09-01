package app.alertify.worker.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.Empty;

import app.alertify.worker.grpc.AlertExecutionResult;
import app.alertify.worker.grpc.AlertWorkerServiceGrpc;
import app.alertify.worker.grpc.ExecuteAlertRequest;
import app.alertify.worker.grpc.ExecuteAlertResponse;
import app.alertify.worker.grpc.SourceRequired;
import app.alertify.worker.grpc.SynchronizeTemplateRequest;
import app.alertify.worker.grpc.SynchronizeTemplateResponse;
import app.alertify.worker.grpc.WorkerStatusResponse;
import app.alertify.worker.grpc.WorkerTask;
import io.grpc.stub.StreamObserver;

class AlertWorkerGrpcService extends AlertWorkerServiceGrpc.AlertWorkerServiceImplBase {

    private static final Logger LOGGER = LoggerFactory.getLogger(AlertWorkerGrpcService.class);

    private final WorkerRuntimeProperties properties;
    private final AlertTemplateCompiler compiler;
    private final WorkerExecutionTracker tracker;
    private final WorkerExecutionEngine executionEngine;
    private final WorkerInstanceIdentity instanceIdentity;

    AlertWorkerGrpcService(WorkerRuntimeProperties properties, AlertTemplateCompiler compiler, WorkerExecutionTracker tracker, WorkerExecutionEngine executionEngine, WorkerInstanceIdentity instanceIdentity) {
        this.properties = properties;
        this.compiler = compiler;
        this.tracker = tracker;
        this.executionEngine = executionEngine;
        this.instanceIdentity = instanceIdentity;
    }

    @Override
    public void executeAlert(ExecuteAlertRequest request, StreamObserver<ExecuteAlertResponse> responseObserver) {
        LOGGER.info(
            "Received alert execution: executionId={}, alertId={}, alertName={}, template={}",
            request.getExecutionId(), request.getAlertId(), request.getAlertName(),
            request.getTemplateClassName()
        );
        if (!compiler.isAvailable(request.getTemplateClassName(), request.getSourceChecksum())) {
            LOGGER.info(
                "Alert template source is required: executionId={}, template={}, checksum={}",
                request.getExecutionId(), request.getTemplateClassName(), request.getSourceChecksum()
            );
            responseObserver.onNext(
                ExecuteAlertResponse.newBuilder()
                    .setSourceRequired(SourceRequired.newBuilder()
                            .setTemplateClassName(request.getTemplateClassName())
                            .setSourceChecksum(request.getSourceChecksum()))
                    .build());
            responseObserver.onCompleted();
            return;
        }

        executionEngine.execute(request, wrappingObserver(responseObserver));
    }

    @Override
    public void synchronizeTemplate(SynchronizeTemplateRequest request, StreamObserver<SynchronizeTemplateResponse> responseObserver) {
        try {
            compiler.synchronize(request.getTemplateClassName(), request.getSourceChecksum(), request.getSource());
            responseObserver.onNext(SynchronizeTemplateResponse.newBuilder()
                    .setSynchronized(true)
                    .build());
        } catch (RuntimeException exception) {
            LOGGER.warn(
                "Alert template compilation failed: template={}, checksum={}",
                request.getTemplateClassName(), request.getSourceChecksum(), exception
            );
            responseObserver.onNext(SynchronizeTemplateResponse.newBuilder()
                    .setSynchronized(false)
                    .setError(WorkerExecutionEngine.error(exception))
                    .build());
        }
        responseObserver.onCompleted();
    }

    @Override
    public void getStatus(Empty request, StreamObserver<WorkerStatusResponse> responseObserver) {
        var running = tracker.runningTasks();
        var waiting = tracker.waitingTasks();
        WorkerStatusResponse.Builder response = WorkerStatusResponse.newBuilder()
                .setWorkerName(properties.name())
                .setWorkerInstanceId(instanceIdentity.id())
                .addAllCapabilities(properties.capabilities().stream()
                        .map(Enum::name)
                        .sorted()
                        .toList())
                .setTotalExecuted(tracker.totalExecuted())
                .setRunningCount(running.size())
                .setWaitingCount(waiting.size());
        running.stream().map(AlertWorkerGrpcService::task).forEach(response::addRunningTasks);
        waiting.stream().map(AlertWorkerGrpcService::task).forEach(response::addWaitingTasks);
        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
    }

    private static StreamObserver<AlertExecutionResult> wrappingObserver(StreamObserver<ExecuteAlertResponse> responseObserver) {
        return new StreamObserver<>() {
            @Override
            public void onNext(AlertExecutionResult value) {
                responseObserver.onNext(ExecuteAlertResponse.newBuilder().setResult(value).build());
            }

            @Override
            public void onError(Throwable throwable) {
                responseObserver.onError(throwable);
            }

            @Override
            public void onCompleted() {
                responseObserver.onCompleted();
            }
        };
    }

    private static WorkerTask task(WorkerExecutionTracker.TaskState task) {
        WorkerTask.Builder result = WorkerTask.newBuilder()
                .setExecutionId(task.executionId())
                .setAlertId(task.alertId())
                .setAlertName(task.alertName())
                .setQueuedAt(WorkerExecutionEngine.timestamp(task.queuedAt()));
        if (task.workStartedAt() != null)
            result.setWorkStartedAt(WorkerExecutionEngine.timestamp(task.workStartedAt()));

        return result.build();
    }
}
