package app.alertify.grpc;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.io.InputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Iterator;

import org.springframework.stereotype.Component;

import com.google.protobuf.Empty;

import app.alertify.grpc.discovery.WorkerEndpoint;
import app.alertify.worker.grpc.AlertExecutionResult;
import app.alertify.worker.grpc.ArtifactChunk;
import app.alertify.worker.grpc.ArtifactDescriptor;
import app.alertify.worker.grpc.ArtifactRequest;
import app.alertify.worker.grpc.ArtifactWriteHeader;
import app.alertify.worker.grpc.ArtifactWriteRequest;
import app.alertify.worker.grpc.AlertWorkerServiceGrpc;
import app.alertify.worker.grpc.ExecuteAlertRequest;
import app.alertify.worker.grpc.ExecuteProcedureRequest;
import app.alertify.worker.grpc.ExecutionClientMessage;
import app.alertify.worker.grpc.ExecutionWorkerMessage;
import app.alertify.worker.grpc.InvokeProcedureResponse;
import app.alertify.worker.grpc.InvokePipeResponse;
import app.alertify.worker.grpc.PipeInvocationCall;
import app.alertify.worker.grpc.PipeInvocationFailure;
import app.alertify.worker.grpc.PipeInvocationFailureKind;
import app.alertify.worker.grpc.PipeInvocationReply;
import app.alertify.worker.grpc.ProcedureExecutionResult;
import app.alertify.worker.grpc.ProcedureInvocationCall;
import app.alertify.worker.grpc.ProcedureInvocationFailure;
import app.alertify.worker.grpc.ProcedureInvocationFailureKind;
import app.alertify.worker.grpc.ProcedureInvocationReply;
import app.alertify.worker.grpc.SynchronizeTemplateRequest;
import app.alertify.worker.grpc.WorkerStatusResponse;
import io.grpc.ManagedChannel;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.stub.StreamObserver;

/**
 * gRPC client for worker status and per-execution bidirectional streams.
 *
 * <p>One stream carries an execution start message, optional template source
 * synchronization, nested procedure callbacks and its terminal result. A
 * worker can request nested procedures while its template is running; those
 * callbacks are delegated to virtual threads so the gRPC response observer
 * never waits for a nested execution.</p>
 */
@Component
public class AlertWorkerClient implements AutoCloseable {
    private final WorkerGrpcChannelFactory channelFactory;
    private final ExecutorService callbacks = Executors.newVirtualThreadPerTaskExecutor();

    public AlertWorkerClient(WorkerGrpcChannelFactory channelFactory) {
        this.channelFactory = channelFactory;
    }

    public WorkerStatusResponse status(WorkerEndpoint endpoint, Duration timeout) {
        requirePositive(timeout);
        ManagedChannel channel = channelFactory.create(endpoint);
        try {
            return AlertWorkerServiceGrpc.newBlockingStub(channel).withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS).getStatus(Empty.getDefaultInstance());
        } finally {
            shutdown(channel);
        }
    }

    public AlertExecutionResult executeAlert(WorkerEndpoint endpoint, ExecuteAlertRequest request, SynchronizeTemplateRequest templateSource, Duration timeout, Function<String, InvokeProcedureResponse> procedureInvoker) {
        TerminalResult result = execute(endpoint, ExecutionClientMessage.newBuilder().setStartAlert(request).build(), templateSource, timeout, procedureInvoker);
        if (result.alertResult() == null)
            throw new IllegalStateException("Worker returned a procedure result for an alert execution");

        return result.alertResult();
    }

    public ProcedureExecutionResult executeProcedure(WorkerEndpoint endpoint, ExecuteProcedureRequest request, SynchronizeTemplateRequest templateSource, Duration timeout, Function<String, InvokeProcedureResponse> procedureInvoker) {
        return executeProcedure(endpoint, request, templateSource, timeout, procedureInvoker,
                _ -> InvokePipeResponse.newBuilder().setFailure(PipeInvocationFailure.newBuilder()
                        .setKind(PipeInvocationFailureKind.PIPE_INVOCATION_FAILURE_KIND_ERROR)
                        .setMessage("Pipe invocation is unavailable")).build());
    }

    public ProcedureExecutionResult executeProcedure(WorkerEndpoint endpoint, ExecuteProcedureRequest request, SynchronizeTemplateRequest templateSource, Duration timeout, Function<String, InvokeProcedureResponse> procedureInvoker, Function<String, InvokePipeResponse> pipeInvoker) {
        TerminalResult result = execute(endpoint, ExecutionClientMessage.newBuilder().setStartProcedure(request).build(), templateSource, timeout, procedureInvoker, pipeInvoker);
        if (result.procedureResult() == null)
            throw new IllegalStateException("Worker returned an alert result for a procedure execution");

        return result.procedureResult();
    }

    private TerminalResult execute(WorkerEndpoint endpoint, ExecutionClientMessage start, SynchronizeTemplateRequest templateSource, Duration timeout, Function<String, InvokeProcedureResponse> procedureInvoker) {
        return execute(endpoint, start, templateSource, timeout, procedureInvoker,
                _ -> InvokePipeResponse.newBuilder().setFailure(PipeInvocationFailure.newBuilder()
                        .setKind(PipeInvocationFailureKind.PIPE_INVOCATION_FAILURE_KIND_ERROR)
                        .setMessage("Pipe invocation is unavailable")).build());
    }

    private TerminalResult execute(WorkerEndpoint endpoint, ExecutionClientMessage start, SynchronizeTemplateRequest templateSource, Duration timeout, Function<String, InvokeProcedureResponse> procedureInvoker, Function<String, InvokePipeResponse> pipeInvoker) {
        requirePositive(timeout);
        ManagedChannel channel = channelFactory.create(endpoint);
        CompletableFuture<TerminalResult> terminal = new CompletableFuture<>();
        StreamWriter writer = new StreamWriter();
        try {
            AlertWorkerServiceGrpc.AlertWorkerServiceStub stub = AlertWorkerServiceGrpc.newStub(channel).withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS);
            writer.attach(stub.execute(responses(writer, templateSource, procedureInvoker, pipeInvoker, terminal)));
            writer.send(start);
            try {
                return terminal.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Worker execution was interrupted", exception);
            } catch (ExecutionException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof RuntimeException runtimeException)
                    throw runtimeException;

                throw new IllegalStateException("Worker execution failed", cause);
            } catch (TimeoutException exception) {
                throw new IllegalStateException("Worker execution timed out", exception);
            }
        } finally {
            writer.complete();
            shutdown(channel);
        }
    }

    private StreamObserver<ExecutionWorkerMessage> responses(StreamWriter writer, SynchronizeTemplateRequest templateSource, Function<String, InvokeProcedureResponse> procedureInvoker, Function<String, InvokePipeResponse> pipeInvoker, CompletableFuture<TerminalResult> terminal) {
        return new StreamObserver<>() {
            @Override
            public void onNext(ExecutionWorkerMessage message) {
                switch (message.getPayloadCase()) {
                    case SOURCE_REQUIRED -> synchronize(message, writer, templateSource, terminal);
                    case TEMPLATE_SYNCHRONIZATION -> {
                        if (!message.getTemplateSynchronization().getSynchronized())
                            terminal.completeExceptionally(new WorkerTemplateSynchronizationException(message.getTemplateSynchronization().getError()));
                    }
                    case PROCEDURE_CALL -> callbacks.submit(() -> invokeProcedure(message.getProcedureCall(), writer, procedureInvoker));
                    case PIPE_CALL -> callbacks.submit(() -> invokePipe(message.getPipeCall(), writer, pipeInvoker));
                    case ALERT_RESULT -> terminal.complete(new TerminalResult(message.getAlertResult(), null));
                    case PROCEDURE_RESULT -> terminal.complete(new TerminalResult(null, message.getProcedureResult()));
                    case PAYLOAD_NOT_SET -> terminal.completeExceptionally(new IllegalStateException("Worker sent an empty execution message"));
                }
            }

            @Override
            public void onError(Throwable throwable) {
                terminal.completeExceptionally(throwable);
            }

            @Override
            public void onCompleted() {
                if (!terminal.isDone())
                    terminal.completeExceptionally(new IllegalStateException("Worker closed the stream without an execution result"));
            }
        };
    }

    private static void invokePipe(PipeInvocationCall call, StreamWriter writer, Function<String, InvokePipeResponse> pipeInvoker) {
        InvokePipeResponse response;
        try {
            response = pipeInvoker.apply(call.getInvocationToken());
        } catch (RuntimeException exception) {
            response = InvokePipeResponse.newBuilder().setFailure(PipeInvocationFailure.newBuilder()
                    .setKind(PipeInvocationFailureKind.PIPE_INVOCATION_FAILURE_KIND_ERROR)
                    .setMessage(exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage())).build();
        }
        PipeInvocationReply.Builder reply = PipeInvocationReply.newBuilder().setInvocationId(call.getInvocationId());
        if (response.hasResult())
            reply.setResult(response.getResult());
        else if (response.hasFailure())
            reply.setFailure(response.getFailure());
        else
            reply.setFailure(PipeInvocationFailure.newBuilder().setKind(PipeInvocationFailureKind.PIPE_INVOCATION_FAILURE_KIND_ERROR).setMessage("Pipe invocation returned no outcome"));

        writer.send(ExecutionClientMessage.newBuilder().setPipeReply(reply).build());
    }

    public ArtifactDescriptor transferArtifact(WorkerEndpoint source, WorkerEndpoint destination, ArtifactDescriptor descriptor, Instant expiresAt, Duration timeout) {
        requirePositive(timeout);
        ManagedChannel sourceChannel = channelFactory.create(source);
        ManagedChannel destinationChannel = channelFactory.create(destination);
        ArtifactUpload upload = new ArtifactUpload(timeout, "Artifact transfer");
        try {
            Iterator<ArtifactChunk> chunks = AlertWorkerServiceGrpc.newBlockingStub(sourceChannel)
                    .withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .readArtifact(ArtifactRequest.newBuilder().setArtifactId(descriptor.getArtifactId()).build());
            AlertWorkerServiceGrpc.newStub(destinationChannel).withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS).writeArtifact(upload);
            upload.send(ArtifactWriteRequest.newBuilder().setHeader(writeHeader(descriptor, expiresAt)).build());
            while (chunks.hasNext())
                upload.send(ArtifactWriteRequest.newBuilder().setChunk(chunks.next()).build());
            upload.complete();
            return upload.await();
        } catch (RuntimeException exception) {
            throw exception;
        } finally {
            shutdown(sourceChannel);
            shutdown(destinationChannel);
        }
    }

    public ArtifactDescriptor uploadArtifact(WorkerEndpoint destination, String outputKey, String fileName, String mediaType, long size, byte[] sha256, InputStream input, Instant expiresAt, Duration timeout) {
        requirePositive(timeout);
        ArtifactDescriptor expected = ArtifactDescriptor.newBuilder().setOutputKey(outputKey).setFileName(fileName)
                .setMediaType(mediaType).setSize(size).setSha256(com.google.protobuf.ByteString.copyFrom(sha256)).build();
        ManagedChannel channel = channelFactory.create(destination);
        ArtifactUpload upload = new ArtifactUpload(timeout, "Artifact upload");
        try (input) {
            AlertWorkerServiceGrpc.newStub(channel).withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS).writeArtifact(upload);
            upload.send(ArtifactWriteRequest.newBuilder().setHeader(writeHeader(expected, expiresAt)).build());
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0)
                    upload.send(ArtifactWriteRequest.newBuilder().setChunk(ArtifactChunk.newBuilder()
                            .setData(com.google.protobuf.ByteString.copyFrom(buffer, 0, read))).build());
            }
            upload.complete();
            return upload.await();
        } catch (IOException exception) {
            throw new IllegalStateException("Artifact upload failed", exception);
        } finally {
            shutdown(channel);
        }
    }

    public void deleteArtifact(WorkerEndpoint endpoint, String artifactId, Duration timeout) {
        ManagedChannel channel = channelFactory.create(endpoint);
        try {
            AlertWorkerServiceGrpc.newBlockingStub(channel).withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .deleteArtifact(ArtifactRequest.newBuilder().setArtifactId(artifactId).build());
        } finally {
            shutdown(channel);
        }
    }

    private static ArtifactWriteHeader writeHeader(ArtifactDescriptor descriptor, Instant expiresAt) {
        return ArtifactWriteHeader.newBuilder().setOutputKey(descriptor.getOutputKey()).setFileName(descriptor.getFileName())
                .setMediaType(descriptor.getMediaType()).setExpectedSize(descriptor.getSize())
                .setExpectedSha256(descriptor.getSha256()).setExpiresAt(expiresAt.toString()).build();
    }

    private static <T> T await(CompletableFuture<T> result, Duration timeout, String operation) {
        try {
            return result.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(operation + " was interrupted", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException(operation + " failed", exception.getCause());
        } catch (TimeoutException exception) {
            throw new IllegalStateException(operation + " timed out", exception);
        }
    }

    private static void synchronize(ExecutionWorkerMessage message, StreamWriter writer, SynchronizeTemplateRequest templateSource, CompletableFuture<TerminalResult> terminal) {
        if (!message.getSourceRequired().getTemplateClassName().equals(templateSource.getTemplateClassName()) || !message.getSourceRequired().getSourceChecksum().equals(templateSource.getSourceChecksum())) {
            terminal.completeExceptionally(new IllegalStateException("Worker requested an unexpected template source"));
            return;
        }
        writer.send(ExecutionClientMessage.newBuilder().setTemplateSource(templateSource).build());
    }

    private static void invokeProcedure(ProcedureInvocationCall call, StreamWriter writer, Function<String, InvokeProcedureResponse> procedureInvoker) {
        InvokeProcedureResponse response;
        try {
            response = procedureInvoker.apply(call.getInvocationToken());
        } catch (RuntimeException exception) {
            response = InvokeProcedureResponse.newBuilder().setFailure(ProcedureInvocationFailure.newBuilder().setKind(ProcedureInvocationFailureKind.PROCEDURE_INVOCATION_FAILURE_KIND_ERROR).setMessage(exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage())).build();
        }
        ProcedureInvocationReply.Builder reply = ProcedureInvocationReply.newBuilder().setInvocationId(call.getInvocationId());
        if (response.hasResult())
            reply.setResult(response.getResult());
        else if (response.hasFailure())
            reply.setFailure(response.getFailure());
        else
            reply.setFailure(ProcedureInvocationFailure.newBuilder().setKind(ProcedureInvocationFailureKind.PROCEDURE_INVOCATION_FAILURE_KIND_ERROR).setMessage("Procedure invocation returned no outcome"));

        writer.send(ExecutionClientMessage.newBuilder().setProcedureReply(reply).build());
    }

    private static void requirePositive(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative())
            throw new IllegalArgumentException("gRPC timeout must be positive");
    }

    private static void shutdown(ManagedChannel channel) {
        channel.shutdownNow();
        try {
            channel.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        callbacks.close();
    }

    /**
     * Terminal outcome of one execution stream.
     *
     * <p>Exactly one result field is populated, according to whether the stream
     * started an alert or a procedure.</p>
     */
    private record TerminalResult(AlertExecutionResult alertResult, ProcedureExecutionResult procedureResult) {
    }

    /**
     * Serializes client messages sent by the execution owner and asynchronous
     * nested-procedure callbacks.
     *
     * <p>gRPC request observers are not safe for concurrent writes. This guard
     * also prevents messages from being sent after the client closes the
     * stream.</p>
     */
    private static final class StreamWriter {
        private StreamObserver<ExecutionClientMessage> observer;
        private boolean closed;

        synchronized void attach(StreamObserver<ExecutionClientMessage> value) {
            observer = value;
        }

        synchronized void send(ExecutionClientMessage message) {
            if (!closed)
                observer.onNext(message);
        }

        synchronized void complete() {
            if (closed || observer == null)
                return;

            closed = true;
            observer.onCompleted();
        }
    }

    /** Client-streaming writer that propagates gRPC flow control to the source reader. */
    private static final class ArtifactUpload implements ClientResponseObserver<ArtifactWriteRequest, ArtifactDescriptor> {
        private final CompletableFuture<ArtifactDescriptor> result = new CompletableFuture<>();
        private final long deadlineNanos;
        private final String operation;
        private final Object readiness = new Object();
        private ClientCallStreamObserver<ArtifactWriteRequest> request;

        private ArtifactUpload(Duration timeout, String operation) {
            deadlineNanos = System.nanoTime() + timeout.toNanos();
            this.operation = operation;
        }

        @Override
        public void beforeStart(ClientCallStreamObserver<ArtifactWriteRequest> requestStream) {
            synchronized (readiness) {
                request = requestStream;
                requestStream.setOnReadyHandler(() -> {
                    synchronized (readiness) {
                        readiness.notifyAll();
                    }
                });
                readiness.notifyAll();
            }
        }

        void send(ArtifactWriteRequest message) {
            ClientCallStreamObserver<ArtifactWriteRequest> stream = readyStream();
            stream.onNext(message);
        }

        void complete() {
            ClientCallStreamObserver<ArtifactWriteRequest> stream;
            synchronized (readiness) {
                stream = request;
            }
            if (stream == null)
                throw new IllegalStateException(operation + " stream did not start");

            stream.onCompleted();
        }

        ArtifactDescriptor await() {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0)
                throw new IllegalStateException(operation + " timed out");

            return AlertWorkerClient.await(result, Duration.ofNanos(remaining), operation);
        }

        private ClientCallStreamObserver<ArtifactWriteRequest> readyStream() {
            synchronized (readiness) {
                while ((request == null || !request.isReady()) && !result.isDone()) {
                    long remaining = deadlineNanos - System.nanoTime();
                    if (remaining <= 0)
                        throw new IllegalStateException(operation + " timed out waiting for stream capacity");

                    try {
                        TimeUnit.NANOSECONDS.timedWait(readiness, remaining);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(operation + " was interrupted", exception);
                    }
                }
                if (result.isDone()) {
                    long remaining = Math.max(1, deadlineNanos - System.nanoTime());
                    AlertWorkerClient.await(result, Duration.ofNanos(remaining), operation);
                    throw new IllegalStateException(operation + " stream closed before all content was sent");
                }

                return request;
            }
        }

        @Override
        public void onNext(ArtifactDescriptor value) {
            result.complete(value);
            signal();
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
            signal();
        }

        @Override
        public void onCompleted() {
            if (!result.isDone())
                result.completeExceptionally(new IllegalStateException("Worker closed artifact write without a descriptor"));
            signal();
        }

        private void signal() {
            synchronized (readiness) {
                readiness.notifyAll();
            }
        }
    }
}
