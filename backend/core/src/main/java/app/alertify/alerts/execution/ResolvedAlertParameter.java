package app.alertify.alerts.execution;

import app.alertify.alerts.template.annotation.AlertParameterSource;

/**
 * One alert parameter resolved to what will be sent to the worker. Text,
 * configuration and secret bindings carry their value, while a procedure
 * binding carries only {@code procedureId} and is later replaced by an
 * invocation token. {@code writable} marks a binding whose value the template
 * may write back.
 */
public record ResolvedAlertParameter(
    String name,
    String javaType,
    String value,
    boolean nullValue,
    AlertParameterSource source,
    Long configurationId,
    Long secretId,
    Long procedureId,
    boolean writable
) {
    ResolvedAlertParameter(String name, String javaType, String value, boolean nullValue,
            AlertParameterSource source, Long configurationId, Long secretId, boolean writable) {
        this(name, javaType, value, nullValue, source, configurationId, secretId, null, writable);
    }

    ResolvedAlertParameter(String name, String javaType, String value, boolean nullValue) {
        this(name, javaType, value, nullValue, AlertParameterSource.TEXT, null, null, null, false);
    }
}
