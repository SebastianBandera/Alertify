package app.alertify.services.secret;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;

@Service
public class SymmetricKeyService {

    private static final String KEY_ALGORITHM = "AES";

    private final DatabaseKeyPartSource databaseKeyPartSource;
    private final EnvironmentKeyPartSource environmentKeyPartSource;
    private final Sha256HashService sha256HashService;

    public SymmetricKeyService(
            DatabaseKeyPartSource databaseKeyPartSource,
            EnvironmentKeyPartSource environmentKeyPartSource,
            Sha256HashService sha256HashService) {
        this.databaseKeyPartSource = databaseKeyPartSource;
        this.environmentKeyPartSource = environmentKeyPartSource;
        this.sha256HashService = sha256HashService;
    }

    public SecretKey getKey() {
        byte[] databaseKeyPart = databaseKeyPartSource.read();
        byte[] environmentKeyPart = environmentKeyPartSource.read().getBytes(StandardCharsets.UTF_8);
        byte[] keyBytes = sha256HashService.hash(encodeKeyParts(databaseKeyPart, environmentKeyPart));
        return new SecretKeySpec(keyBytes, KEY_ALGORITHM);
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
