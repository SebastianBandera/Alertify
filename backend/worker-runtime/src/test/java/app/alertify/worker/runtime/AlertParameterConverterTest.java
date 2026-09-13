package app.alertify.worker.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.google.protobuf.ByteString;

import app.alertify.worker.contract.BinaryPayloadCodec;
import app.alertify.worker.grpc.AlertParameter;

class AlertParameterConverterTest {

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
