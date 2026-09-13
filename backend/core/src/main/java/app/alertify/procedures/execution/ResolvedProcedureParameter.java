package app.alertify.procedures.execution;

import java.util.Arrays;
import java.util.Objects;

import app.alertify.alerts.template.annotation.AlertParameterSource;

/**
 * One procedure parameter resolved to what will be sent to the worker. Text,
 * configuration and secret bindings carry their value, while a procedure
 * binding carries only {@code procedureId} and is later replaced by an
 * invocation token. {@code writable} marks a binding whose value the template
 * may write back.
 */
public record ResolvedProcedureParameter(
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

    @Override
    public boolean equals(Object object) {
        if (this == object)
            return true;

        if (!(object instanceof ResolvedProcedureParameter other))
            return false;

        return nullValue == other.nullValue
                && writable == other.writable
                && Objects.equals(name, other.name)
                && Objects.equals(javaType, other.javaType)
                && Objects.equals(value, other.value)
                && source == other.source
                && Objects.equals(configurationId, other.configurationId)
                && Objects.equals(secretId, other.secretId)
                && Objects.equals(procedureId, other.procedureId)
                && Arrays.equals(binaryZip, other.binaryZip);
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
        return "ResolvedProcedureParameter["
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
