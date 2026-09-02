package app.alertify.alerts.execution;

public record ResolvedAlertParameter(
    String name,
    String javaType,
    String value,
    boolean nullValue,
    Long configurationId,
    boolean writable
) {
    ResolvedAlertParameter(String name, String javaType, String value, boolean nullValue) {
        this(name, javaType, value, nullValue, null, false);
    }
}
