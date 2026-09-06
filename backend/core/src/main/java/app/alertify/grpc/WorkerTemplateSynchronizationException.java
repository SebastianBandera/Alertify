package app.alertify.grpc;

import app.alertify.worker.grpc.ExecutionError;

public class WorkerTemplateSynchronizationException extends RuntimeException {
    private final ExecutionError error;

    public WorkerTemplateSynchronizationException(ExecutionError error) {
        super(error.getMessage().isBlank() ? error.getType() : error.getMessage());
        this.error = error;
    }

    public ExecutionError error() {
        return error;
    }
}
