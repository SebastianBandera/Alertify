package app.alertify.procedures.api;

public record ProcedureDeletionImpactResponse(
    Long procedureId,
    String name,
    long executionCount,
    long alertReferenceCount,
    long procedureReferenceCount
) {
}
