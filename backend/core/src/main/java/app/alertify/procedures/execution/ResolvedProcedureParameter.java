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
    Long pipeId,
    boolean writable,
    Long bindingVersion,
    String binaryFileName,
    String binaryMediaType,
    Long binarySize
) {

    public ResolvedProcedureParameter(String name, String javaType, String value, byte[] binaryZip, boolean nullValue, AlertParameterSource source, Long configurationId, Long secretId, Long procedureId, boolean writable) {
        this(name, javaType, value, binaryZip, nullValue, source, configurationId, secretId, procedureId, null, writable, null, null, null, null);
    }

    public ResolvedProcedureParameter(String name, String javaType, String value, byte[] binaryZip, boolean nullValue, AlertParameterSource source, Long configurationId, Long secretId, Long procedureId, boolean writable, Long bindingVersion) {
        this(name, javaType, value, binaryZip, nullValue, source, configurationId, secretId, procedureId, null, writable, bindingVersion, null, null, null);
    }

    @Override
    public boolean equals(Object object) {
        if (this == object)
            return true;

        if (!(object instanceof ResolvedProcedureParameter(
                var otherName,
                var otherJavaType,
                var otherValue,
                var otherBinaryZip,
                var otherNullValue,
                var otherSource,
                var otherConfigurationId,
                var otherSecretId,
                var otherProcedureId,
                var otherPipeId,
                var otherWritable,
                var otherBindingVersion,
                var otherBinaryFileName,
                var otherBinaryMediaType,
                var otherBinarySize)))
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
                && Objects.equals(pipeId, otherPipeId)
                && Objects.equals(bindingVersion, otherBindingVersion)
                && Objects.equals(binaryFileName, otherBinaryFileName)
                && Objects.equals(binaryMediaType, otherBinaryMediaType)
                && Objects.equals(binarySize, otherBinarySize)
                && Arrays.equals(binaryZip, otherBinaryZip);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
            name, javaType, value, nullValue, source, configurationId, secretId, procedureId, pipeId, writable, bindingVersion,
            binaryFileName, binaryMediaType, binarySize
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
                + ", pipeId=" + pipeId
                + ", writable=" + writable
                + ", bindingVersion=" + bindingVersion
                + ", binaryFileName=" + binaryFileName
                + ", binaryMediaType=" + binaryMediaType
                + ", binarySize=" + binarySize
                + "]";
    }
}
