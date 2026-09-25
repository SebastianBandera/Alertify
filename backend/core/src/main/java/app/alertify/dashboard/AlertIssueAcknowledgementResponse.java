package app.alertify.dashboard;

import java.time.Instant;

/** Instant up to which the current user has seen an alert's issues. */
public record AlertIssueAcknowledgementResponse(long alertId, Instant acknowledgedAt) {
}
