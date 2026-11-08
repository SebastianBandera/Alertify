package app.alertify.playwright;

/** Metadata-only diagnostic. Never includes DSL text or exception messages. */
public record PlaywrightUsageIssue(String code, long alertId, String alertName) { }
