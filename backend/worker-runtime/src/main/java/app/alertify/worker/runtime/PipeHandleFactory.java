package app.alertify.worker.runtime;

import java.util.UUID;

import app.alertify.pipes.Pipe;
import app.alertify.pipes.PipeExecutionException;
import app.alertify.worker.grpc.AlertParameter;
import app.alertify.worker.grpc.InvokePipeResponse;
import io.grpc.Deadline;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class PipeHandleFactory {
    private final PipeInvoker invoker;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    PipeHandleFactory(PipeInvoker invoker) {
        this.invoker = invoker;
    }

    Pipe create(AlertParameter parameter, Deadline deadline) {
        if (parameter.getPipeInvocationToken().isBlank() || parameter.getPipeId() <= 0)
            throw new IllegalArgumentException("Pipe parameter '" + parameter.getName() + "' has no invocation capability");

        return () -> invoke(parameter.getPipeInvocationToken(), deadline);
    }

    private JsonNode invoke(String token, Deadline deadline) {
        InvokePipeResponse response = invoker.invoke(token, deadline);
        if (response.hasFailure()) {
            UUID executionId = response.getFailure().getExecutionId().isBlank()
                    ? null : UUID.fromString(response.getFailure().getExecutionId());
            throw new PipeExecutionException(executionId, response.getFailure().getMessage());
        }
        if (!response.hasResult())
            throw new PipeExecutionException("Pipe dispatcher returned no outcome");

        try {
            JsonNode result = jsonMapper.readTree(response.getResult().getResultJson());
            if (result == null)
                throw new PipeExecutionException("Pipe dispatcher returned an empty JSON result");

            return result;
        } catch (PipeExecutionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new PipeExecutionException(UUID.fromString(response.getResult().getExecutionId()), "Pipe dispatcher returned invalid JSON", exception);
        }
    }
}
