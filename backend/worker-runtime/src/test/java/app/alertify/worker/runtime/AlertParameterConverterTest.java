package app.alertify.worker.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.google.protobuf.ByteString;

import app.alertify.worker.contract.BinaryPayloadCodec;
import app.alertify.worker.contract.GitCredentials;
import app.alertify.worker.contract.GitProvider;
import app.alertify.worker.grpc.AlertParameter;

class AlertParameterConverterTest {

    @Test
    void roundTripsGitCredentialsThroughCanonicalJson() {
        GitCredentials credentials = new GitCredentials(GitProvider.GITLAB, "gitlab.com", null, "glpat-x", null);
        AlertParameter parameter = AlertParameter.newBuilder().setName("credentials")
                .setJavaType(GitCredentials.class.getName()).setValue(credentials.toJson()).build();

        Object value = AlertParameterConverter.convert(parameter, GitCredentials.class);

        assertThat(value).isEqualTo(credentials);
        assertThat(AlertParameterConverter.serialize(value, GitCredentials.class)).isEqualTo(credentials.toJson());
    }

    @Test
    void convertsEmptyPayloadZipToEmptyByteArray() {
        AlertParameter parameter = AlertParameter.newBuilder().setName("blob").setJavaType("[B")
                .setBinaryValue(ByteString.copyFrom(BinaryPayloadCodec.compress(new byte[0], 1024)))
                .build();

        Object value = AlertParameterConverter.convert(parameter, byte[].class);

        assertThat(value).isInstanceOf(byte[].class);
        assertThat((byte[]) value).isEmpty();
    }

    @Test
    void convertsNullBinaryValueToNull() {
        AlertParameter parameter = AlertParameter.newBuilder().setName("blob").setJavaType("[B").setNullValue(true).build();

        assertThat(AlertParameterConverter.convert(parameter, byte[].class)).isNull();
    }

    @Test
    void serializesEmptyByteArrayAsNonEmptyArchive() {
        byte[] archive = AlertParameterConverter.serializeBinary(new byte[0], byte[].class);

        assertThat(archive).isNotEmpty();
        assertThat(BinaryPayloadCodec.decompress(archive, 1024)).isEmpty();
    }
}
