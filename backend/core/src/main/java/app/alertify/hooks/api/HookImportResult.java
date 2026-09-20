package app.alertify.hooks.api;

import java.util.List;

public record HookImportResult(int total, int created, int updated, int unchanged, int skipped, List<HookImportError> errors) {
}
