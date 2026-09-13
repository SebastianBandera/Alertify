package app.alertify.worker.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BinaryPayloadCodecTest {
    @Test
    void roundTripsBinaryContent() {
        byte[] value = new byte[1024 * 1024];
        value[17] = 42;
        byte[] archive = BinaryPayloadCodec.compress(value, value.length);
        assertThat(archive.length).isLessThan(value.length);
        assertThat(BinaryPayloadCodec.decompress(archive, value.length)).isEqualTo(value);
    }

    @Test
    void rejectsLogicalContentOverTheLimit() {
        assertThatThrownBy(() -> BinaryPayloadCodec.compress(new byte[11], 10))
                .isInstanceOf(IllegalArgumentException.class);
        byte[] archive = BinaryPayloadCodec.compress(new byte[11], 11);
        assertThatThrownBy(() -> BinaryPayloadCodec.decompress(archive, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void roundTripsEmptyBinaryContent() {
        byte[] archive = BinaryPayloadCodec.compress(new byte[0], 10);
        assertThat(archive).isNotEmpty();
        assertThat(BinaryPayloadCodec.decompress(archive, 10)).isNotNull().isEmpty();
    }

    @Test
    void rejectsNullValue() {
        assertThatThrownBy(() -> BinaryPayloadCodec.compress(null, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmptyArchive() {
        assertThatThrownBy(() -> BinaryPayloadCodec.decompress(new byte[0], 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BinaryPayloadCodec.decompress(null, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
