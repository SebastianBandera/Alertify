package app.alertify.worker.runtime;

import java.util.UUID;

import app.alertify.procedures.Procedure;
import app.alertify.procedures.ProcedureBusyException;
import app.alertify.procedures.ProcedureDepthExceededException;
import app.alertify.procedures.ProcedureDisabledException;
import app.alertify.procedures.ProcedureExecutionException;
import app.alertify.worker.grpc.AlertParameter;
import app.alertify.worker.grpc.InvokeProcedureResponse;
import app.alertify.worker.grpc.ProcedureInvocationFailure;
import app.alertify.worker.grpc.ProcedureInvocationFailureKind;
import io.grpc.Deadline;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns a procedure-sourced parameter into the lazy {@link Procedure} handle a
 * template calls. The worker never resolves the referenced procedure itself: it
 * carries the opaque invocation token issued by the backend and requests the
 * procedure through its execution stream. The backend enforces depth and the
 * inherited deadline while allowing the same configured handle to be invoked
 * repeatedly. Failures are translated back into the contract exceptions the
 * template code is written against.
 */
class ProcedureHandleFactory {
    private final ProcedureInvoker invoker;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    ProcedureHandleFactory(ProcedureInvoker invoker) {
        this.invoker = invoker;
    }

    Procedure create(AlertParameter parameter, Deadline deadline) {
        if (parameter.getInvocationToken().isBlank() || parameter.getProcedureId() <= 0)
            throw new IllegalArgumentException("Procedure parameter '" + parameter.getName() + "' has no invocation capability");

        return () -> invoke(parameter.getInvocationToken(), deadline);
    }

    private JsonNode invoke(String token, Deadline deadline) {
        InvokeProcedureResponse response = invoker.invoke(token, deadline);
        if (response.hasFailure())
            throw failure(response.getFailure());

        if (!response.hasResult())
            throw new ProcedureExecutionException("Procedure dispatcher returned no outcome");

        try {
            JsonNode result = jsonMapper.readTree(response.getResult().getResultJson());
            if (result == null)
                throw new ProcedureExecutionException("Procedure dispatcher returned an empty JSON result");

            return result;
        } catch (ProcedureExecutionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ProcedureExecutionException(UUID.fromString(response.getResult().getExecutionId()),
                    "Procedure dispatcher returned invalid JSON", exception);
        }
    }

    private static ProcedureExecutionException failure(ProcedureInvocationFailure failure) {
        String message = failure.getMessage().isBlank() ? "Procedure invocation failed" : failure.getMessage();
        UUID executionId = failure.getExecutionId().isBlank() ? null : UUID.fromString(failure.getExecutionId());
        ProcedureInvocationFailureKind kind = failure.getKind();
        return switch (kind) {
            case PROCEDURE_INVOCATION_FAILURE_KIND_DISABLED -> new ProcedureDisabledException(message);
            case PROCEDURE_INVOCATION_FAILURE_KIND_DEPTH_EXCEEDED -> new ProcedureDepthExceededException(message);
            case PROCEDURE_INVOCATION_FAILURE_KIND_BUSY -> new ProcedureBusyException(message);
            case PROCEDURE_INVOCATION_FAILURE_KIND_ERROR,
                    PROCEDURE_INVOCATION_FAILURE_KIND_UNSPECIFIED,
                    UNRECOGNIZED -> new ProcedureExecutionException(executionId, message);
        };
    }
}
