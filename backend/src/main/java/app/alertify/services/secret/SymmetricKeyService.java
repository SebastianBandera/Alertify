package app.alertify.services.secret;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;

@Service
public class SymmetricKeyService {

    private static final String KEY_ALGORITHM = "AES";
    private static final String DERIVATION_ALGORITHM = "SHA-256";

    private final byte[] keyBytes;

    public SymmetricKeyService(EnvironmentKeyPartSource environmentKeyPartSource) {
        // The database-backed source will replace this empty part when it is implemented.
        byte[] databaseKeyPart = new byte[0];
        byte[] environmentKeyPart = environmentKeyPartSource.read().getBytes(StandardCharsets.UTF_8);

        this.keyBytes = deriveKey(databaseKeyPart, environmentKeyPart);
    }

    public SecretKey getKey() {
        return new SecretKeySpec(keyBytes.clone(), KEY_ALGORITHM);
    }

    private static byte[] deriveKey(byte[] databaseKeyPart, byte[] environmentKeyPart) {
        try {
            MessageDigest digest = MessageDigest.getInstance(DERIVATION_ALGORITHM);

            updateDigest(digest, databaseKeyPart);
            updateDigest(digest, environmentKeyPart);

            return digest.digest();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static void updateDigest(MessageDigest digest, byte[] keyPart) {
        byte[] lengthPrefix = ByteBuffer.allocate(Integer.BYTES).putInt(keyPart.length).array();

        digest.update(lengthPrefix);
        digest.update(keyPart);
    }
}
