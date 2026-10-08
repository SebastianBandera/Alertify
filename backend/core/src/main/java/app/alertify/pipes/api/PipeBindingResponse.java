package app.alertify.pipes.api;

public record PipeBindingResponse(String targetParameterKey, String sourceStepKey, String sourceOutputKey, String sourceResultPointer, String valueExpression) {
}
