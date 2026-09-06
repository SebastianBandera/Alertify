package app.alertify.worker.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import app.alertify.worker.grpc.ExecutionWorkerMessage;
import app.alertify.worker.grpc.ProcedureInvocationReply;
import app.alertify.worker.grpc.ProcedureInvocationResult;

class StreamProcedureInvokerTest {

    @Test
    void correlatesConcurrentRepliesThatArriveOutOfOrder() throws Exception {
        List<ExecutionWorkerMessage> output = new CopyOnWriteArrayList<>();
        StreamProcedureInvoker invoker = new StreamProcedureInvoker(output::add);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> invoker.invoke("first-token", null).getResult().getResultJson(), executor);
            CompletableFuture<String> second = CompletableFuture.supplyAsync(() -> invoker.invoke("second-token", null).getResult().getResultJson(), executor);
            while (output.size() < 2)
                Thread.sleep(5);

            Map<String, String> ids = output.stream().collect(java.util.stream.Collectors.toMap(message -> message.getProcedureCall().getInvocationToken(), message -> message.getProcedureCall().getInvocationId()));
            invoker.complete(reply(ids.get("second-token"), "{\"order\":2}"));
            invoker.complete(reply(ids.get("first-token"), "{\"order\":1}"));

            assertThat(first.get(1, TimeUnit.SECONDS)).isEqualTo("{\"order\":1}");
            assertThat(second.get(1, TimeUnit.SECONDS)).isEqualTo("{\"order\":2}");
        } finally {
            invoker.close();
        }
    }

    @Test
    void closingTheStreamFailsAProcedureThatIsWaitingForItsReply() throws Exception {
        List<ExecutionWorkerMessage> output = new CopyOnWriteArrayList<>();
        StreamProcedureInvoker invoker = new StreamProcedureInvoker(output::add);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<?> invocation = CompletableFuture.supplyAsync(() -> invoker.invoke("token", null), executor);
            while (output.isEmpty())
                Thread.sleep(5);

            invoker.close(new IllegalStateException("stream cancelled"));

            assertThatThrownBy(() -> invocation.get(1, TimeUnit.SECONDS)).hasRootCauseMessage("stream cancelled");
        }
    }

    private static ProcedureInvocationReply reply(String invocationId, String json) {
        return ProcedureInvocationReply.newBuilder().setInvocationId(invocationId).setResult(ProcedureInvocationResult.newBuilder().setExecutionId(java.util.UUID.randomUUID().toString()).setResultJson(json)).build();
    }
}
