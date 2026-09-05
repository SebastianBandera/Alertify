package app.alertify.alerts.execution;

import app.alertify.alerts.template.annotation.AlertParameterSource;

public record ResolvedAlertParameter(
    String name,
    String javaType,
    String value,
    boolean nullValue,
    AlertParameterSource source,
    Long configurationId,
    Long secretId,
    boolean writable
) {
    ResolvedAlertParameter(String name, String javaType, String value, boolean nullValue) {
        this(name, javaType, value, nullValue, AlertParameterSource.TEXT, null, null, false);
    }
}
