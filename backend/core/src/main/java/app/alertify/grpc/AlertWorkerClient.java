package app.alertify.grpc;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import org.springframework.stereotype.Component;

import com.google.protobuf.Empty;

import app.alertify.grpc.discovery.WorkerEndpoint;
import app.alertify.worker.grpc.AlertExecutionResult;
import app.alertify.worker.grpc.AlertWorkerServiceGrpc;
import app.alertify.worker.grpc.ExecuteAlertRequest;
import app.alertify.worker.grpc.ExecuteProcedureRequest;
import app.alertify.worker.grpc.ExecutionClientMessage;
import app.alertify.worker.grpc.ExecutionWorkerMessage;
import app.alertify.worker.grpc.InvokeProcedureResponse;
import app.alertify.worker.grpc.ProcedureExecutionResult;
import app.alertify.worker.grpc.ProcedureInvocationCall;
import app.alertify.worker.grpc.ProcedureInvocationFailure;
import app.alertify.worker.grpc.ProcedureInvocationFailureKind;
import app.alertify.worker.grpc.ProcedureInvocationReply;
import app.alertify.worker.grpc.SynchronizeTemplateRequest;
import app.alertify.worker.grpc.WorkerStatusResponse;
import io.grpc.ManagedChannel;
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
        TerminalResult result = execute(endpoint, ExecutionClientMessage.newBuilder().setStartProcedure(request).build(), templateSource, timeout, procedureInvoker);
        if (result.procedureResult() == null)
            throw new IllegalStateException("Worker returned an alert result for a procedure execution");

        return result.procedureResult();
    }

    private TerminalResult execute(WorkerEndpoint endpoint, ExecutionClientMessage start, SynchronizeTemplateRequest templateSource, Duration timeout, Function<String, InvokeProcedureResponse> procedureInvoker) {
        requirePositive(timeout);
        ManagedChannel channel = channelFactory.create(endpoint);
        CompletableFuture<TerminalResult> terminal = new CompletableFuture<>();
        StreamWriter writer = new StreamWriter();
        try {
            AlertWorkerServiceGrpc.AlertWorkerServiceStub stub = AlertWorkerServiceGrpc.newStub(channel).withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS);
            writer.attach(stub.execute(responses(writer, templateSource, procedureInvoker, terminal)));
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

    private StreamObserver<ExecutionWorkerMessage> responses(StreamWriter writer, SynchronizeTemplateRequest templateSource, Function<String, InvokeProcedureResponse> procedureInvoker, CompletableFuture<TerminalResult> terminal) {
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
}
