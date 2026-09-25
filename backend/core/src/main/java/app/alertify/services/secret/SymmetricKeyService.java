package app.alertify.services.secret;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Derives the AES key from length-delimited database, source-code and
 * environment key parts, then hashes the combined material to a fixed-size
 * key. Changing any part can make previously stored secrets unrecoverable.
 */
@Service
public class SymmetricKeyService {

    static final String TRANSITION_DELIMITER = "->";

    private final DatabaseKeyPartSource databaseKeyPartSource;
    private final PrivateClassKeyPartSource privateClassKeyPartSource;
    private final EnvironmentKeyPartSource environmentKeyPartSource;
    private final Sha256HashService sha256HashService;
    private volatile ObfuscatedKeyMaterial activeKey;

    public SymmetricKeyService(DatabaseKeyPartSource databaseKeyPartSource, PrivateClassKeyPartSource privateClassKeyPartSource, EnvironmentKeyPartSource environmentKeyPartSource, Sha256HashService sha256HashService) {
        this.databaseKeyPartSource = databaseKeyPartSource;
        this.privateClassKeyPartSource = privateClassKeyPartSource;
        this.environmentKeyPartSource = environmentKeyPartSource;
        this.sha256HashService = sha256HashService;
    }

    public SecretKey getKey() {
        ObfuscatedKeyMaterial current = activeKey;
        if (current == null)
            throw new IllegalStateException("The symmetric key has not been initialized");

        return current.revealKey();
    }

    SymmetricKeyRotation readRotation() {
        KeyPart database = parse(databaseKeyPartSource.read(), "database", false);
        KeyPart privateClass = parse(privateClassKeyPartSource.read(), "private class", true);
        KeyPart environment = parse(environmentKeyPartSource.read(), "environment", false);
        byte[] oldKeyBytes = derive(database.oldValue(), privateClass.oldValue(), environment.oldValue());
        byte[] newKeyBytes = derive(database.newValue(), privateClass.newValue(), environment.newValue());
        try {
            return new SymmetricKeyRotation(
                    database.transition() || privateClass.transition() || environment.transition(),
                    ObfuscatedKeyMaterial.from(oldKeyBytes),
                    ObfuscatedKeyMaterial.from(newKeyBytes)
            );
        } finally {
            Arrays.fill(oldKeyBytes, (byte) 0);
            Arrays.fill(newKeyBytes, (byte) 0);
        }
    }

    synchronized void activate(ObfuscatedKeyMaterial keyMaterial) {
        ObfuscatedKeyMaterial replacement = keyMaterial.copy();
        ObfuscatedKeyMaterial previous = activeKey;
        activeKey = replacement;
        if (previous != null)
            previous.destroy();
    }

    public boolean activeKeyMatchesCurrentTarget() {
        ObfuscatedKeyMaterial current = activeKey;
        if (current == null)
            return false;

        try (SymmetricKeyRotation rotation = readRotation()) {
            return current.hasSameKey(rotation.newKey());
        }
    }

    private byte[] derive(String database, String privateClass, String environment) {
        byte[] databaseBytes = database.getBytes(StandardCharsets.UTF_8);
        byte[] privateClassBytes = privateClass.getBytes(StandardCharsets.UTF_8);
        byte[] environmentBytes = environment.getBytes(StandardCharsets.UTF_8);
        byte[] encoded = encodeKeyParts(databaseBytes, privateClassBytes, environmentBytes);
        try {
            return sha256HashService.hash(encoded);
        } finally {
            Arrays.fill(databaseBytes, (byte) 0);
            Arrays.fill(privateClassBytes, (byte) 0);
            Arrays.fill(environmentBytes, (byte) 0);
            Arrays.fill(encoded, (byte) 0);
        }
    }

    private static KeyPart parse(String rawValue, String source, boolean emptyAllowed) {
        int delimiterIndex = rawValue.indexOf(TRANSITION_DELIMITER);
        if (delimiterIndex < 0) {
            if (!emptyAllowed && rawValue.isEmpty())
                throw new IllegalStateException("The " + source + " symmetric-key part must not be empty");

            return new KeyPart(false, rawValue, rawValue);
        }
        if (delimiterIndex != rawValue.lastIndexOf(TRANSITION_DELIMITER))
            throw new IllegalStateException("The " + source + " symmetric-key part contains more than one transition delimiter");

        String oldValue = rawValue.substring(0, delimiterIndex);
        String newValue = rawValue.substring(delimiterIndex + TRANSITION_DELIMITER.length());
        if (!emptyAllowed && (oldValue.isEmpty() || newValue.isEmpty()))
            throw new IllegalStateException("The " + source + " symmetric-key transition must have non-empty old and new values");

        return new KeyPart(true, oldValue, newValue);
    }

    private static byte[] encodeKeyParts(byte[] databaseKeyPart, byte[] privateClassKeyPart, byte[] environmentKeyPart) {
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

    private record KeyPart(boolean transition, String oldValue, String newValue) { }
}
