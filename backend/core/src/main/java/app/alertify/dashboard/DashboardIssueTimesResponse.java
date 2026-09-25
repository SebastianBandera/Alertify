package app.alertify.dashboard;

import java.time.Instant;

/**
 * Latest WARN and ERROR of an alert with persistent issues, counted since the
 * option was turned on. The same for every user: each client compares them
 * with the instant its own user last marked the alert's issues as seen.
 *
 * @param lastWarnAt  when the latest WARN finished, or null when there was none
 * @param lastErrorAt when the latest ERROR finished, or null when there was none
 */
public record DashboardIssueTimesResponse(Instant lastWarnAt, Instant lastErrorAt) {
}
