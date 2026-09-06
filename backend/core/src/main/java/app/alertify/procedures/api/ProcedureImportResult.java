package app.alertify.procedures.api;

public record ProcedureImportResult(int total, int created, int updated, int unchanged, int tagsCreated) {
}
