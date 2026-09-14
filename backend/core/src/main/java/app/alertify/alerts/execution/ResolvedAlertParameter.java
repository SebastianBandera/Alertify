package app.alertify.alerts.execution;

import java.util.Arrays;
import java.util.Objects;

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
    byte[] binaryZip,
    boolean nullValue,
    AlertParameterSource source,
    Long configurationId,
    Long secretId,
    Long procedureId,
    boolean writable
) {
    ResolvedAlertParameter(String name, String javaType, String value, boolean nullValue,
            AlertParameterSource source, Long configurationId, Long secretId, Long procedureId, boolean writable) {
        this(name, javaType, value, null, nullValue, source, configurationId, secretId, procedureId, writable);
    }
    ResolvedAlertParameter(String name, String javaType, String value, boolean nullValue, AlertParameterSource source, Long configurationId, Long secretId, boolean writable) {
        this(name, javaType, value, null, nullValue, source, configurationId, secretId, null, writable);
    }

    ResolvedAlertParameter(String name, String javaType, String value, boolean nullValue) {
        this(name, javaType, value, null, nullValue, AlertParameterSource.TEXT, null, null, null, false);
    }

    @Override
    public boolean equals(Object object) {
        if (this == object)
            return true;

        if (!(object instanceof ResolvedAlertParameter(
                var otherName,
                var otherJavaType,
                var otherValue,
                var otherBinaryZip,
                var otherNullValue,
                var otherSource,
                var otherConfigurationId,
                var otherSecretId,
                var otherProcedureId,
                var otherWritable)))
            return false;

        return nullValue == otherNullValue
                && writable == otherWritable
                && Objects.equals(name, otherName)
                && Objects.equals(javaType, otherJavaType)
                && Objects.equals(value, otherValue)
                && source == otherSource
                && Objects.equals(configurationId, otherConfigurationId)
                && Objects.equals(secretId, otherSecretId)
                && Objects.equals(procedureId, otherProcedureId)
                && Arrays.equals(binaryZip, otherBinaryZip);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
            name, javaType, value, nullValue, source, configurationId, secretId, procedureId, writable
        );
        return 31 * result + Arrays.hashCode(binaryZip);
    }

    @Override
    public String toString() {
        return "ResolvedAlertParameter["
                + "name=" + name
                + ", javaType=" + javaType
                + ", valuePresent=" + (value != null)
                + ", binaryZipLength=" + (binaryZip == null ? "null" : binaryZip.length)
                + ", nullValue=" + nullValue
                + ", source=" + source
                + ", configurationId=" + configurationId
                + ", secretId=" + secretId
                + ", procedureId=" + procedureId
                + ", writable=" + writable
                + "]";
    }
}
