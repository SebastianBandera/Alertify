package app.alertify.services.secret;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;

@Service
public class SymmetricKeyService {

    private static final String KEY_ALGORITHM = "AES";

    private final byte[] keyBytes;

    public SymmetricKeyService(EnvironmentKeyPartSource environmentKeyPartSource, Sha256HashService sha256HashService) {
        // The database-backed source will replace this empty part when it is implemented.
        byte[] databaseKeyPart = new byte[0];
        byte[] environmentKeyPart = environmentKeyPartSource.read().getBytes(StandardCharsets.UTF_8);

        this.keyBytes = sha256HashService.hash(encodeKeyParts(databaseKeyPart, environmentKeyPart));
    }

    public SecretKey getKey() {
        return new SecretKeySpec(keyBytes.clone(), KEY_ALGORITHM);
    }

    private static byte[] encodeKeyParts(byte[] databaseKeyPart, byte[] environmentKeyPart) {
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES + databaseKeyPart.length + Integer.BYTES + environmentKeyPart.length);

        appendKeyPart(buffer, databaseKeyPart);
        appendKeyPart(buffer, environmentKeyPart);

        return buffer.array();
    }

    private static void appendKeyPart(ByteBuffer buffer, byte[] keyPart) {
        buffer.putInt(keyPart.length);
        buffer.put(keyPart);
    }
}
