package app.alertify.services.secret;

import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.SecretKey;

/**
 * Keeps key bytes as two random XOR shares so the complete AES key is not
 * retained as a contiguous byte sequence in the heap between operations.
 */
final class ObfuscatedKeyMaterial {

    private static final String KEY_ALGORITHM = "AES";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Object COMPARISON_TIE_LOCK = new Object();

    private final Object lock = new Object();
    private final byte[] mask;
    private final byte[] maskedKey;
    private boolean destroyed;

    private ObfuscatedKeyMaterial(byte[] mask, byte[] maskedKey) {
        this.mask = mask;
        this.maskedKey = maskedKey;
    }

    static ObfuscatedKeyMaterial from(byte[] keyBytes) {
        byte[] mask = new byte[keyBytes.length];
        byte[] maskedKey = new byte[keyBytes.length];
        RANDOM.nextBytes(mask);
        for (int index = 0; index < keyBytes.length; index++)
            maskedKey[index] = (byte) (keyBytes[index] ^ mask[index]);

        return new ObfuscatedKeyMaterial(mask, maskedKey);
    }

    SecretKey revealKey() {
        synchronized (lock) {
            ensureAvailable();
            byte[] keyBytes = revealBytes();
            try {
                return new WipeableSecretKey(keyBytes);
            } finally {
                Arrays.fill(keyBytes, (byte) 0);
            }
        }
    }

    static void destroyRevealedKey(SecretKey key) {
        if (key instanceof WipeableSecretKey wipeable)
            wipeable.destroy();
    }

    ObfuscatedKeyMaterial copy() {
        synchronized (lock) {
            ensureAvailable();
            byte[] keyBytes = revealBytes();
            try {
                return from(keyBytes);
            } finally {
                Arrays.fill(keyBytes, (byte) 0);
            }
        }
    }

    boolean hasSameKey(ObfuscatedKeyMaterial other) {
        int order = Integer.compare(System.identityHashCode(lock), System.identityHashCode(other.lock));
        if (order < 0)
            return compareWith(other);

        if (order > 0)
            return other.compareWith(this);

        // Serialize equal identity hashes so reversed comparisons cannot invert lock order.
        synchronized (COMPARISON_TIE_LOCK) {
            return compareWith(other);
        }
    }

    private boolean compareWith(ObfuscatedKeyMaterial other) {
        synchronized (lock) {
            synchronized (other.lock) {
                return compareLocked(other);
            }
        }
    }

    private boolean compareLocked(ObfuscatedKeyMaterial other) {
        ensureAvailable();
        other.ensureAvailable();
        if (mask.length != other.mask.length)
            return false;

        int difference = 0;
        for (int index = 0; index < mask.length; index++) {
            difference |= (mask[index] ^ maskedKey[index])
                    ^ (other.mask[index] ^ other.maskedKey[index]);
        }
        return difference == 0;
    }

    void destroy() {
        synchronized (lock) {
            Arrays.fill(mask, (byte) 0);
            Arrays.fill(maskedKey, (byte) 0);
            destroyed = true;
        }
    }

    private byte[] revealBytes() {
        byte[] keyBytes = new byte[mask.length];
        for (int index = 0; index < mask.length; index++)
            keyBytes[index] = (byte) (mask[index] ^ maskedKey[index]);

        return keyBytes;
    }

    private void ensureAvailable() {
        if (destroyed)
            throw new IllegalStateException("Symmetric key material has been destroyed");
    }

    private static final class WipeableSecretKey implements SecretKey {

        private static final long serialVersionUID = 1L;
        private byte[] keyBytes;

        private WipeableSecretKey(byte[] keyBytes) {
            this.keyBytes = keyBytes.clone();
        }

        @Override
        public String getAlgorithm() {
            return KEY_ALGORITHM;
        }

        @Override
        public String getFormat() {
            return "RAW";
        }

        @Override
        public synchronized byte[] getEncoded() {
            if (keyBytes == null)
                throw new IllegalStateException("Symmetric key has been destroyed");

            return keyBytes.clone();
        }

        @Override
        public synchronized void destroy() {
            if (keyBytes != null) {
                Arrays.fill(keyBytes, (byte) 0);
                keyBytes = null;
            }
        }

        @Override
        public synchronized boolean isDestroyed() {
            return keyBytes == null;
        }
    }
}
