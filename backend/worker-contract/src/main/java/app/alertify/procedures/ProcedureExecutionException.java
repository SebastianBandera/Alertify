package app.alertify.procedures;

import java.util.UUID;

/** Base exception exposed to an evaluator when a procedure invocation fails. */
public class ProcedureExecutionException extends RuntimeException {

    private final UUID executionId;

    public ProcedureExecutionException(String message) {
        this(null, message, null);
    }

    public ProcedureExecutionException(UUID executionId, String message) {
        this(executionId, message, null);
    }

    public ProcedureExecutionException(UUID executionId, String message, Throwable cause) {
        super(message, cause);
        this.executionId = executionId;
    }

    public UUID getExecutionId() {
        return executionId;
    }
}
