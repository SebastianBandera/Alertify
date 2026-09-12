package app.alertify.system;

/** Published when the clock enters or leaves the configured cron quiet window. */
public record CronQuietHoursTransitionEvent(boolean active) {
}
