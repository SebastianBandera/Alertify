package app.alertify.pipes;

import java.util.UUID;

public class PipeExecutionException extends RuntimeException {
    private final UUID executionId;

    public PipeExecutionException(String message) {
        this(null, message, null);
    }

    public PipeExecutionException(UUID executionId, String message) {
        this(executionId, message, null);
    }

    public PipeExecutionException(UUID executionId, String message, Throwable cause) {
        super(message, cause);
        this.executionId = executionId;
    }

    public UUID getExecutionId() { return executionId; }
}
