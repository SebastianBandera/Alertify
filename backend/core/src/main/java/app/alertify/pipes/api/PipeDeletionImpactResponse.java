package app.alertify.pipes.api;

public record PipeDeletionImpactResponse(long executionCount, long hookReferenceCount, long procedureReferenceCount) {
}
