package app.alertify.pipes.model;

public enum PipeStepStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    WARN,
    ERROR,
    SKIPPED_DISABLED,
    SKIPPED_SEQUENCE,
    MISSING_PIPE_OUTPUT,
    ARTIFACT_UNAVAILABLE
}
