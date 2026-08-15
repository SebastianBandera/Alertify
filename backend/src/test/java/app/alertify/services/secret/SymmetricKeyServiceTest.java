package app.alertify.services.secret;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.junit.jupiter.api.Test;

class SymmetricKeyServiceTest {

    @Test
    void derivesAes256KeyWithDatabasePartBeforeEnvironmentPart() throws Exception {
        byte[] environmentKeyPart = "environment-key-part".getBytes(StandardCharsets.UTF_8);
        SymmetricKeyService service = new SymmetricKeyService(new EnvironmentKeyPartSource("environment-key-part"));

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(0).array());
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(environmentKeyPart.length).array());
        digest.update(environmentKeyPart);

        assertEquals("AES", service.getKey().getAlgorithm());
        assertEquals(32, service.getKey().getEncoded().length);
        assertArrayEquals(digest.digest(), service.getKey().getEncoded());
    }

    @Test
    void rejectsBlankEnvironmentPart() {
        assertThrows(IllegalStateException.class, () -> new EnvironmentKeyPartSource(" "));
    }
}
