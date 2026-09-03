package app.alertify.alerts.api;

public record AlertImportResult(
    int total,
    int created,
    int updated,
    int unchanged,
    int tagsCreated
) {
}
