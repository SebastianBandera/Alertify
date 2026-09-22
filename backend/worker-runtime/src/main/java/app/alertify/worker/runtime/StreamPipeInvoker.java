package app.alertify.worker.runtime;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import app.alertify.pipes.PipeExecutionException;
import app.alertify.worker.grpc.ExecutionWorkerMessage;
import app.alertify.worker.grpc.InvokePipeResponse;
import app.alertify.worker.grpc.PipeInvocationCall;
import app.alertify.worker.grpc.PipeInvocationReply;
import io.grpc.Deadline;

/**
 * Lets a template running on the worker invoke a Pipe synchronously over the bidirectional
 * execution stream that the backend opened for the current alert or procedure.
 * <p>
 * The worker cannot reach the backend on its own, so the call travels as a
 * {@link PipeInvocationCall} message on the same stream and the backend answers with a
 * {@link PipeInvocationReply}. Each call gets a random invocation id that correlates the
 * reply with the {@link CompletableFuture} the calling thread is blocked on. The
 * {@code invocationToken} carried by the call is issued and validated by the backend
 * ({@code PipeInvocationTokenService}); this class only forwards it.
 * <p>
 * One instance exists per execution stream and is owned by
 * {@link AlertWorkerGrpcService}, which feeds replies through {@link #complete} and
 * closes the invoker when the stream ends, failing every pending call so no template
 * thread stays blocked after the backend has gone away.
 */
final class StreamPipeInvoker implements PipeInvoker, AutoCloseable {
    private final Consumer<ExecutionWorkerMessage> output;
    private final ConcurrentMap<String, CompletableFuture<InvokePipeResponse>> pending = new ConcurrentHashMap<>();
    private Throwable closedCause;

    StreamPipeInvoker(Consumer<ExecutionWorkerMessage> output) {
        this.output = output;
    }

    /**
     * Sends the invocation to the backend and blocks until its reply arrives, the optional
     * deadline expires or the stream is closed. Every outcome other than a reply surfaces as a
     * {@link PipeExecutionException}.
     */
    @Override
    public InvokePipeResponse invoke(String token, Deadline deadline) {
        String invocationId = UUID.randomUUID().toString();
        CompletableFuture<InvokePipeResponse> response = new CompletableFuture<>();
        synchronized (this) {
            if (closedCause != null)
                throw new PipeExecutionException(null, "Execution stream is closed", closedCause);

            pending.put(invocationId, response);
        }
        try {
            output.accept(ExecutionWorkerMessage.newBuilder().setPipeCall(PipeInvocationCall.newBuilder().setInvocationId(invocationId).setInvocationToken(token)).build());
            if (deadline == null)
                return response.get();

            long remaining = deadline.timeRemaining(TimeUnit.NANOSECONDS);
            if (remaining <= 0)
                throw new PipeExecutionException("Pipe invocation deadline has expired");

            return response.get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PipeExecutionException(null, "Pipe invocation was interrupted", exception);
        } catch (TimeoutException exception) {
            throw new PipeExecutionException(null, "Pipe invocation deadline has expired", exception);
        } catch (ExecutionException exception) {
            throw new PipeExecutionException(null, "Pipe invocation failed", exception.getCause());
        } finally {
            pending.remove(invocationId);
        }
    }

    /**
     * Delivers a backend reply to the call waiting for it.
     *
     * @return {@code false} when the invocation id is unknown, for example because the caller
     *         already gave up on its deadline
     */
    boolean complete(PipeInvocationReply reply) {
        CompletableFuture<InvokePipeResponse> response = pending.remove(reply.getInvocationId());
        if (response == null)
            return false;

        InvokePipeResponse.Builder result = InvokePipeResponse.newBuilder();
        if (reply.hasResult())
            result.setResult(reply.getResult());
        else if (reply.hasFailure())
            result.setFailure(reply.getFailure());
        else {
            response.completeExceptionally(new PipeExecutionException("Pipe reply has no outcome"));
            return true;
        }
        response.complete(result.build());
        return true;
    }

    /** Rejects new calls and fails every pending one with {@code cause}; safe to call more than once. */
    void close(Throwable cause) {
        java.util.List<CompletableFuture<InvokePipeResponse>> responses;
        synchronized (this) {
            if (closedCause != null)
                return;

            closedCause = cause;
            responses = java.util.List.copyOf(pending.values());
            pending.clear();
        }
        responses.forEach(response -> response.completeExceptionally(cause));
    }

    @Override
    public void close() {
        close(new PipeExecutionException("Execution stream was closed"));
    }
}
