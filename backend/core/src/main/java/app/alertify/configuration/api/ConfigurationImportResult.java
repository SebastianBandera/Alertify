package app.alertify.configuration.api;

public record ConfigurationImportResult(
    int total,
    int created,
    int updated,
    int unchanged,
    int tagsCreated
) {
}
