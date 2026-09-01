package app.alertify.alerts.execution;

public record ResolvedAlertParameter(
    String name,
    String javaType,
    String value,
    boolean nullValue
) {
}
