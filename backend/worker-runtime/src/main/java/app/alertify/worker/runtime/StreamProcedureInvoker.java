package app.alertify.worker.runtime;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import app.alertify.procedures.ProcedureExecutionException;
import app.alertify.worker.grpc.ExecutionWorkerMessage;
import app.alertify.worker.grpc.InvokeProcedureResponse;
import app.alertify.worker.grpc.ProcedureInvocationCall;
import app.alertify.worker.grpc.ProcedureInvocationReply;
import io.grpc.Deadline;

/** Correlates synchronous template calls with replies on one execution stream. */
final class StreamProcedureInvoker implements ProcedureInvoker, AutoCloseable {
    private final Consumer<ExecutionWorkerMessage> output;
    private final ConcurrentMap<String, CompletableFuture<InvokeProcedureResponse>> pending = new ConcurrentHashMap<>();
    private volatile Throwable closedCause;

    StreamProcedureInvoker(Consumer<ExecutionWorkerMessage> output) {
        this.output = output;
    }

    @Override
    public InvokeProcedureResponse invoke(String token, Deadline deadline) {
        String invocationId = UUID.randomUUID().toString();
        CompletableFuture<InvokeProcedureResponse> response = new CompletableFuture<>();
        synchronized (this) {
            Throwable cause = closedCause;
            if (cause != null)
                throw new ProcedureExecutionException(null, "Execution stream is closed", cause);

            pending.put(invocationId, response);
        }
        try {
            output.accept(ExecutionWorkerMessage.newBuilder().setProcedureCall(ProcedureInvocationCall.newBuilder().setInvocationId(invocationId).setInvocationToken(token)).build());
            if (deadline == null)
                return response.get();

            long remaining = deadline.timeRemaining(TimeUnit.NANOSECONDS);
            if (remaining <= 0)
                throw new ProcedureExecutionException("Procedure invocation deadline has expired");

            return response.get(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ProcedureExecutionException(null, "Procedure invocation was interrupted", exception);
        } catch (TimeoutException exception) {
            throw new ProcedureExecutionException(null, "Procedure invocation deadline has expired", exception);
        } catch (ExecutionException exception) {
            Throwable failure = exception.getCause();
            if (failure instanceof RuntimeException runtimeException)
                throw runtimeException;

            throw new ProcedureExecutionException(null, "Procedure invocation failed", failure);
        } finally {
            pending.remove(invocationId);
        }
    }

    boolean complete(ProcedureInvocationReply reply) {
        CompletableFuture<InvokeProcedureResponse> response = pending.remove(reply.getInvocationId());
        if (response == null)
            return false;

        InvokeProcedureResponse.Builder result = InvokeProcedureResponse.newBuilder();
        if (reply.hasResult())
            result.setResult(reply.getResult());
        else if (reply.hasFailure())
            result.setFailure(reply.getFailure());
        else {
            response.completeExceptionally(new ProcedureExecutionException("Procedure reply has no outcome"));
            return true;
        }
        response.complete(result.build());
        return true;
    }

    void close(Throwable cause) {
        java.util.List<CompletableFuture<InvokeProcedureResponse>> responses;
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
        close(new ProcedureExecutionException("Execution stream was closed"));
    }
}
