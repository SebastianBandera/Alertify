package app.alertify.system;

/** Published after a system configuration transaction commits. */
public record SystemConfigurationChangedEvent(boolean maintenanceModeChanged, boolean cronQuietHoursChanged) {
}
