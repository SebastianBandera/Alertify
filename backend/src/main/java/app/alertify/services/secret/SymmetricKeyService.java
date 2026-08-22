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
    private final PrivateClassKeyPartSource privateClassKeyPartSource;
    private final EnvironmentKeyPartSource environmentKeyPartSource;
    private final Sha256HashService sha256HashService;

    public SymmetricKeyService(
            DatabaseKeyPartSource databaseKeyPartSource,
            PrivateClassKeyPartSource privateClassKeyPartSource,
            EnvironmentKeyPartSource environmentKeyPartSource,
            Sha256HashService sha256HashService) {
        this.databaseKeyPartSource = databaseKeyPartSource;
        this.privateClassKeyPartSource = privateClassKeyPartSource;
        this.environmentKeyPartSource = environmentKeyPartSource;
        this.sha256HashService = sha256HashService;
    }

    public SecretKey getKey() {
        byte[] databaseKeyPart = databaseKeyPartSource.read();
        byte[] privateClassKeyPart = privateClassKeyPartSource.read().getBytes(StandardCharsets.UTF_8);
        byte[] environmentKeyPart = environmentKeyPartSource.read().getBytes(StandardCharsets.UTF_8);
        byte[] keyBytes = sha256HashService.hash(
            encodeKeyParts(databaseKeyPart, privateClassKeyPart, environmentKeyPart)
        );
        return new SecretKeySpec(keyBytes, KEY_ALGORITHM);
    }

    private static byte[] encodeKeyParts(
            byte[] databaseKeyPart,
            byte[] privateClassKeyPart,
            byte[] environmentKeyPart) {
        int privateClassEncodedLength = privateClassKeyPart.length == 0
            ? 0
            : Integer.BYTES + privateClassKeyPart.length;
        ByteBuffer buffer = ByteBuffer.allocate(
            Integer.BYTES + databaseKeyPart.length
                + privateClassEncodedLength
                + Integer.BYTES + environmentKeyPart.length
        );

        appendKeyPart(buffer, databaseKeyPart);
        if (privateClassKeyPart.length > 0) {
            appendKeyPart(buffer, privateClassKeyPart);
        }
        appendKeyPart(buffer, environmentKeyPart);

        return buffer.array();
    }

    private static void appendKeyPart(ByteBuffer buffer, byte[] keyPart) {
        buffer.putInt(keyPart.length);
        buffer.put(keyPart);
    }
}
