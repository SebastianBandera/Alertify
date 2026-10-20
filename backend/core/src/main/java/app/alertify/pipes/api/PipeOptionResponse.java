package app.alertify.pipes.api;

import java.util.List;

import app.alertify.pipes.model.PipeStepType;

public record PipeOptionResponse(
    long id,
    String name,
    String templateKey,
    boolean enabled,
    PipeStepType type,
    List<String> outputs,
    List<String> artifactInputs,
    List<String> stringInputs
) {
}
