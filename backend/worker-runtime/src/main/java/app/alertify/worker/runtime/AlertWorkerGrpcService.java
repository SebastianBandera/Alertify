package app.alertify.worker.runtime;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.google.protobuf.Empty;

import app.alertify.procedures.ProcedureExecutionException;
import app.alertify.worker.grpc.ArtifactChunk;
import app.alertify.worker.grpc.ArtifactDescriptor;
import app.alertify.worker.grpc.ArtifactRequest;
import app.alertify.worker.grpc.ArtifactWriteHeader;
import app.alertify.worker.grpc.ArtifactWriteRequest;
import app.alertify.worker.grpc.AlertExecutionResult;
import app.alertify.worker.grpc.AlertWorkerServiceGrpc;
import app.alertify.worker.grpc.ExecuteAlertRequest;
import app.alertify.worker.grpc.ExecuteProcedureRequest;
import app.alertify.worker.grpc.ExecutionClientMessage;
import app.alertify.worker.grpc.ExecutionWorkerMessage;
import app.alertify.worker.grpc.ProcedureExecutionResult;
import app.alertify.worker.grpc.SourceRequired;
import app.alertify.worker.grpc.SynchronizeTemplateRequest;
import app.alertify.worker.grpc.SynchronizeTemplateResponse;
import app.alertify.worker.grpc.TemplateKind;
import app.alertify.worker.grpc.WorkerStatusResponse;
import app.alertify.worker.grpc.WorkerTask;
import app.alertify.worker.grpc.WorkerTaskKind;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;

/**
 * gRPC entry point exposed by every worker.
 *
 * <p>Each alert or procedure execution owns one bidirectional stream. The
 * stream carries the initial execution request, optional template source
 * synchronization, nested procedure invocations and the terminal result. This
 * keeps all communication for an execution on the worker's normal gRPC port
 * and avoids a reverse callback channel.</p>
 */
class AlertWorkerGrpcService extends AlertWorkerServiceGrpc.AlertWorkerServiceImplBase implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(AlertWorkerGrpcService.class);
    private final WorkerRuntimeProperties properties;
    private final AlertTemplateCompiler compiler;
    private final WorkerExecutionTracker tracker;
    private final WorkerExecutionEngine executionEngine;
    private final ProcedureExecutionEngine procedureExecutionEngine;
    private final WorkerInstanceIdentity instanceIdentity;
    private final WorkerResourceMonitor resourceMonitor;
    private final WorkerArtifactStore artifactStore;
    private final ExecutorService sourceSynchronizations = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService artifactTransfers = Executors.newVirtualThreadPerTaskExecutor();

    @Autowired
    AlertWorkerGrpcService(WorkerRuntimeProperties properties, AlertTemplateCompiler compiler, WorkerExecutionTracker tracker, WorkerExecutionEngine executionEngine, ProcedureExecutionEngine procedureExecutionEngine, WorkerInstanceIdentity instanceIdentity, WorkerResourceMonitor resourceMonitor) {
        this(properties, compiler, tracker, executionEngine, procedureExecutionEngine, instanceIdentity, resourceMonitor, procedureExecutionEngine.artifactStore());
    }

    AlertWorkerGrpcService(WorkerRuntimeProperties properties, AlertTemplateCompiler compiler, WorkerExecutionTracker tracker, WorkerExecutionEngine executionEngine, ProcedureExecutionEngine procedureExecutionEngine, WorkerInstanceIdentity instanceIdentity, WorkerResourceMonitor resourceMonitor, WorkerArtifactStore artifactStore) {
        this.properties = properties;
        this.compiler = compiler;
        this.tracker = tracker;
        this.executionEngine = executionEngine;
        this.procedureExecutionEngine = procedureExecutionEngine;
        this.instanceIdentity = instanceIdentity;
        this.resourceMonitor = resourceMonitor;
        this.artifactStore = artifactStore;
    }

    @Override
    public StreamObserver<ExecutionClientMessage> execute(StreamObserver<ExecutionWorkerMessage> responseObserver) {
        ExecutionSession session = new ExecutionSession(responseObserver, Context.current().getDeadline());
        if (responseObserver instanceof ServerCallStreamObserver<ExecutionWorkerMessage> serverObserver)
            serverObserver.setOnCancelHandler(session::cancel);

        return session;
    }

    @Override
    public void getStatus(Empty request, StreamObserver<WorkerStatusResponse> responseObserver) {
        var running = tracker.runningTasks();
        var waiting = tracker.waitingTasks();
        WorkerStatusResponse.Builder response = WorkerStatusResponse.newBuilder()
                .setWorkerName(properties.name())
                .setWorkerInstanceId(instanceIdentity.id())
                .setWorkerStartedAt(WorkerExecutionEngine.timestamp(instanceIdentity.startedAt()))
                .setMaxConcurrentAlerts(properties.maxConcurrentAlerts())
                .addAllCapabilities(properties.capabilities().stream().map(Enum::name).sorted().toList())
                .setTotalExecuted(tracker.totalExecuted())
                .setRunningCount(running.size())
                .setWaitingCount(waiting.size())
                .setResourceUsage(resourceMonitor.usage());
        var procedures = tracker.runningProcedureTasks();
        response.setTotalExecutedProcedures(tracker.totalExecutedProcedures()).setRunningProcedureCount(procedures.size());
        running.stream().map(AlertWorkerGrpcService::task).forEach(response::addRunningTasks);
        waiting.stream().map(AlertWorkerGrpcService::task).forEach(response::addWaitingTasks);
        procedures.stream().map(AlertWorkerGrpcService::procedureTask).forEach(response::addRunningProcedures);
        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
    }

    @Override
    public void readArtifact(ArtifactRequest request, StreamObserver<ArtifactChunk> responseObserver) {
        artifactTransfers.submit(() -> {
            try (var input = artifactStore.open(request.getArtifactId())) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0)
                        continue;

                    if (responseObserver instanceof ServerCallStreamObserver<ArtifactChunk> serverObserver) {
                        while (!serverObserver.isReady() && !serverObserver.isCancelled())
                            java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(1));
                        if (serverObserver.isCancelled())
                            return;
                    }
                    responseObserver.onNext(ArtifactChunk.newBuilder().setData(com.google.protobuf.ByteString.copyFrom(buffer, 0, read)).build());
                }
                responseObserver.onCompleted();
            } catch (RuntimeException | java.io.IOException exception) {
                responseObserver.onError(Status.NOT_FOUND.withDescription("Artifact is unavailable").withCause(exception).asRuntimeException());
            }
        });
    }

    @Override
    public StreamObserver<ArtifactWriteRequest> writeArtifact(StreamObserver<ArtifactDescriptor> responseObserver) {
        return new StreamObserver<>() {
            private ArtifactWriteHeader header;
            private WorkerArtifactStore.Writer writer;
            private boolean closed;

            @Override
            public void onNext(ArtifactWriteRequest request) {
                try {
                    if (closed)
                        throw new IllegalArgumentException("Artifact write is already closed");
                    if (request.hasHeader()) {
                        if (header != null)
                            throw new IllegalArgumentException("Artifact header must be sent exactly once");

                        header = request.getHeader();
                        writer = artifactStore.create(header.getOutputKey(), header.getFileName(), header.getMediaType(), java.time.Instant.parse(header.getExpiresAt()));
                    } else if (request.hasChunk()) {
                        if (writer == null)
                            throw new IllegalArgumentException("Artifact header must precede content");

                        request.getChunk().getData().writeTo(writer);
                    } else {
                        throw new IllegalArgumentException("Artifact write message has no payload");
                    }
                } catch (RuntimeException | java.io.IOException exception) {
                    fail(exception);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                closed = true;
                if (writer != null)
                    writer.abort();
            }

            @Override
            public void onCompleted() {
                if (closed)
                    return;

                try {
                    if (writer == null || header == null)
                        throw new IllegalArgumentException("Artifact write has no header");

                    writer.close();
                    ArtifactDescriptor descriptor = writer.descriptor();
                    if (descriptor.getSize() != header.getExpectedSize()
                            || !descriptor.getSha256().equals(header.getExpectedSha256())) {
                        writer.abort();
                        throw new IllegalStateException("Transferred artifact size or SHA-256 does not match");
                    }
                    closed = true;
                    responseObserver.onNext(descriptor);
                    responseObserver.onCompleted();
                } catch (RuntimeException | java.io.IOException exception) {
                    fail(exception);
                }
            }

            private void fail(Throwable exception) {
                if (closed)
                    return;

                closed = true;
                if (writer != null)
                    writer.abort();
                responseObserver.onError(Status.DATA_LOSS.withDescription(exception.getMessage()).withCause(exception).asRuntimeException());
            }
        };
    }

    @Override
    public void deleteArtifact(ArtifactRequest request, StreamObserver<Empty> responseObserver) {
        try {
            artifactStore.delete(request.getArtifactId());
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        } catch (RuntimeException exception) {
            responseObserver.onError(Status.INTERNAL.withDescription("Artifact cleanup failed").withCause(exception).asRuntimeException());
        }
    }

    @Override
    public void close() {
        sourceSynchronizations.close();
        artifactTransfers.close();
    }

    /**
     * Stateful server-side endpoint for one execution stream.
     *
     * <p>The session enforces message ordering, coordinates asynchronous source
     * compilation and creates the per-stream procedure invoker. It deliberately
     * does not hold its monitor while an alert or procedure waits for a nested
     * procedure result.</p>
     */
    private final class ExecutionSession implements StreamObserver<ExecutionClientMessage> {
        private final SerializedOutput output;
        private final StreamProcedureInvoker procedureInvoker;
        private final StreamPipeInvoker pipeInvoker;
        private final io.grpc.Deadline deadline;
        private ExecuteAlertRequest alert;
        private ExecuteProcedureRequest procedure;
        private boolean awaitingSource;
        private boolean synchronizing;
        private boolean executionStarted;
        private boolean terminated;

        private ExecutionSession(StreamObserver<ExecutionWorkerMessage> responseObserver, io.grpc.Deadline deadline) {
            output = new SerializedOutput(responseObserver);
            procedureInvoker = new StreamProcedureInvoker(output::next);
            pipeInvoker = new StreamPipeInvoker(output::next);
            this.deadline = deadline;
        }

        @Override
        public void onNext(ExecutionClientMessage message) {
            try {
                switch (message.getPayloadCase()) {
                    case START_ALERT -> startAlert(message.getStartAlert());
                    case START_PROCEDURE -> startProcedure(message.getStartProcedure());
                    case TEMPLATE_SOURCE -> synchronize(message.getTemplateSource());
                    case PROCEDURE_REPLY -> procedureReply(message);
                    case PIPE_REPLY -> pipeReply(message);
                    case PAYLOAD_NOT_SET -> invalid("Execution message has no payload");
                }
            } catch (RuntimeException exception) {
                fail(Status.INVALID_ARGUMENT.withDescription(exception.getMessage()).withCause(exception));
            }
        }

        private synchronized void startAlert(ExecuteAlertRequest request) {
            requireInitialMessage();
            alert = request;
            LOGGER.info("Received alert execution: executionId={}, alertId={}, alertName={}, template={}", request.getExecutionId(), request.getAlertId(), request.getAlertName(), request.getTemplateClassName());
            if (!compiler.isAvailable(request.getTemplateClassName(), request.getSourceChecksum())) {
                awaitingSource = true;
                LOGGER.info("Alert template source is required: executionId={}, template={}, checksum={}", request.getExecutionId(), request.getTemplateClassName(), request.getSourceChecksum());
                output.next(ExecutionWorkerMessage.newBuilder().setSourceRequired(sourceRequired(request.getTemplateClassName(), request.getSourceChecksum())).build());
                return;
            }
            startExecution();
        }

        private synchronized void startProcedure(ExecuteProcedureRequest request) {
            requireInitialMessage();
            procedure = request;
            LOGGER.info("Received procedure execution: executionId={}, procedureId={}, procedureName={}, template={}, depth={}", request.getExecutionId(), request.getProcedureId(), request.getProcedureName(), request.getTemplateClassName(), request.getDepth());
            if (!compiler.isProcedureAvailable(request.getTemplateClassName(), request.getSourceChecksum())) {
                awaitingSource = true;
                output.next(ExecutionWorkerMessage.newBuilder().setSourceRequired(sourceRequired(request.getTemplateClassName(), request.getSourceChecksum())).build());
                return;
            }
            startExecution();
        }

        private synchronized void synchronize(SynchronizeTemplateRequest source) {
            if (terminated || !awaitingSource || synchronizing || executionStarted)
                invalid("Template source is not expected in the current stream state");

            String expectedClass = alert == null ? procedure.getTemplateClassName() : alert.getTemplateClassName();
            String expectedChecksum = alert == null ? procedure.getSourceChecksum() : alert.getSourceChecksum();
            TemplateKind expectedKind = alert == null ? TemplateKind.TEMPLATE_KIND_PROCEDURE : TemplateKind.TEMPLATE_KIND_ALERT;
            if (!expectedClass.equals(source.getTemplateClassName()) || !expectedChecksum.equals(source.getSourceChecksum()) || expectedKind != source.getTemplateKind())
                invalid("Template source does not match the execution start message");

            synchronizing = true;
            sourceSynchronizations.submit(() -> compile(source));
        }

        private void compile(SynchronizeTemplateRequest source) {
            try {
                compiler.synchronize(source.getTemplateClassName(), source.getSourceChecksum(), source.getSource(), source.getTemplateKind());
                output.next(ExecutionWorkerMessage.newBuilder().setTemplateSynchronization(SynchronizeTemplateResponse.newBuilder().setSynchronized(true)).build());
                synchronized (this) {
                    synchronizing = false;
                    awaitingSource = false;
                    startExecution();
                }
            } catch (RuntimeException exception) {
                LOGGER.warn("Template compilation failed: template={}, checksum={}", source.getTemplateClassName(), source.getSourceChecksum(), exception);
                output.next(ExecutionWorkerMessage.newBuilder().setTemplateSynchronization(SynchronizeTemplateResponse.newBuilder().setSynchronized(false).setError(WorkerExecutionEngine.error(exception))).build());
                synchronized (this) {
                    terminated = true;
                }
                procedureInvoker.close(exception);
                pipeInvoker.close(exception);
                output.complete();
            }
        }

        private synchronized void procedureReply(ExecutionClientMessage message) {
            if (terminated || !executionStarted)
                invalid("Procedure reply is not expected before execution starts");

            if (!procedureInvoker.complete(message.getProcedureReply()))
                invalid("Procedure reply has an unknown invocation id");
        }

        private synchronized void pipeReply(ExecutionClientMessage message) {
            if (terminated || !executionStarted)
                invalid("Pipe reply is not expected before execution starts");

            if (!pipeInvoker.complete(message.getPipeReply()))
                invalid("Pipe reply has an unknown invocation id");
        }

        private void startExecution() {
            if (terminated || executionStarted)
                invalid("Execution has already started");

            executionStarted = true;
            ProcedureHandleFactory handles = new ProcedureHandleFactory(procedureInvoker, pipeInvoker);
            if (alert != null)
                executionEngine.execute(alert, alertObserver(), deadline, handles);
            else
                procedureExecutionEngine.execute(procedure, procedureObserver(), deadline, handles);
        }

        private void requireInitialMessage() {
            if (terminated || alert != null || procedure != null)
                invalid("The stream must contain exactly one initial execution message");
        }

        private void invalid(String message) {
            throw new IllegalArgumentException(message);
        }

        private void fail(Status status) {
            synchronized (this) {
                if (terminated)
                    return;

                terminated = true;
            }
            procedureInvoker.close(status.asRuntimeException());
            pipeInvoker.close(status.asRuntimeException());
            output.error(status.asRuntimeException());
        }

        private void cancel() {
            synchronized (this) {
                terminated = true;
            }
            procedureInvoker.close(new ProcedureExecutionException("Execution stream was cancelled"));
            pipeInvoker.close(new ProcedureExecutionException("Execution stream was cancelled"));
        }

        @Override
        public void onError(Throwable throwable) {
            synchronized (this) {
                terminated = true;
            }
            procedureInvoker.close(throwable);
            pipeInvoker.close(throwable);
        }

        @Override
        public void onCompleted() {
            synchronized (this) {
                if (terminated)
                    return;

                if (!executionStarted)
                    fail(Status.INVALID_ARGUMENT.withDescription("Execution stream closed before execution started"));
            }
        }

        private StreamObserver<AlertExecutionResult> alertObserver() {
            return new StreamObserver<>() {
                @Override
                public void onNext(AlertExecutionResult value) {
                    output.next(ExecutionWorkerMessage.newBuilder().setAlertResult(value).build());
                }

                @Override
                public void onError(Throwable throwable) {
                    fail(Status.INTERNAL.withCause(throwable).withDescription("Alert execution stream failed"));
                }

                @Override
                public void onCompleted() {
                    finish();
                }
            };
        }

        private StreamObserver<ProcedureExecutionResult> procedureObserver() {
            return new StreamObserver<>() {
                @Override
                public void onNext(ProcedureExecutionResult value) {
                    output.next(ExecutionWorkerMessage.newBuilder().setProcedureResult(value).build());
                }

                @Override
                public void onError(Throwable throwable) {
                    fail(Status.INTERNAL.withCause(throwable).withDescription("Procedure execution stream failed"));
                }

                @Override
                public void onCompleted() {
                    finish();
                }
            };
        }

        private void finish() {
            synchronized (this) {
                terminated = true;
            }
            procedureInvoker.close();
            pipeInvoker.close();
            output.complete();
        }
    }

    private static SourceRequired sourceRequired(String className, String checksum) {
        return SourceRequired.newBuilder().setTemplateClassName(className).setSourceChecksum(checksum).build();
    }

    private static WorkerTask task(WorkerExecutionTracker.TaskState task) {
        WorkerTask.Builder result = WorkerTask.newBuilder().setExecutionId(task.executionId()).setAlertId(task.alertId()).setAlertName(task.alertName()).setKind(WorkerTaskKind.WORKER_TASK_KIND_ALERT).setQueuedAt(WorkerExecutionEngine.timestamp(task.queuedAt()));
        if (task.workStartedAt() != null)
            result.setWorkStartedAt(WorkerExecutionEngine.timestamp(task.workStartedAt()));

        return result.build();
    }

    private static WorkerTask procedureTask(WorkerExecutionTracker.ProcedureTaskState task) {
        WorkerTask.Builder result = WorkerTask.newBuilder().setExecutionId(task.executionId()).setAlertId(task.procedureId()).setAlertName(task.procedureName()).setKind(WorkerTaskKind.WORKER_TASK_KIND_PROCEDURE).setDepth(task.depth()).setQueuedAt(WorkerExecutionEngine.timestamp(task.workStartedAt())).setWorkStartedAt(WorkerExecutionEngine.timestamp(task.workStartedAt()));
        if (task.parentExecutionId() != null && !task.parentExecutionId().isBlank())
            result.setParentExecutionId(task.parentExecutionId());

        return result.build();
    }

    /**
     * Serializes writes to a gRPC response observer shared by compilation,
     * execution and nested-procedure callbacks.
     *
     * <p>gRPC observers are not safe for concurrent writes. This small guard
     * preserves message order and ensures that completion or failure is sent at
     * most once.</p>
     */
    private static final class SerializedOutput {
        private final StreamObserver<ExecutionWorkerMessage> observer;
        private boolean closed;

        private SerializedOutput(StreamObserver<ExecutionWorkerMessage> observer) {
            this.observer = observer;
        }

        synchronized void next(ExecutionWorkerMessage message) {
            if (!closed)
                observer.onNext(message);
        }

        synchronized void complete() {
            if (closed)
                return;

            closed = true;
            observer.onCompleted();
        }

        synchronized void error(Throwable throwable) {
            if (closed)
                return;

            closed = true;
            observer.onError(throwable);
        }
    }
}
