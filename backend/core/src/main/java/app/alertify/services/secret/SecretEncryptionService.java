package app.alertify.services.secret;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import org.springframework.stereotype.Service;

import app.alertify.api.error.InvalidSecretValueException;
import app.alertify.jpa.entity.ApplicationSecret;

/**
 * Encrypts secret values with AES-GCM and verifies decrypted bytes against a
 * salted SHA-256 hash. A verification or key-version failure is reported as
 * an unrecoverable secret rather than returning untrusted plaintext.
 */
@Service
public class SecretEncryptionService {

    static final int MAX_VALUE_BYTES = 1024 * 1024;
    static final short CURRENT_ENCRYPTION_VERSION = 1;
    static final int IV_LENGTH = 12;
    static final int HASH_SALT_LENGTH = 16;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";

    private final SymmetricKeyService symmetricKeyService;
    private final Sha256HashService sha256HashService;
    private final SecureRandom secureRandom = new SecureRandom();

    public SecretEncryptionService(SymmetricKeyService symmetricKeyService, Sha256HashService sha256HashService) {
        this.symmetricKeyService = symmetricKeyService;
        this.sha256HashService = sha256HashService;
    }

    public EncryptedSecretValue encrypt(String value) {
        if (value == null)
            throw new InvalidSecretValueException("Secret value must not be null");

        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
        if (valueBytes.length == 0)
            throw new InvalidSecretValueException("Secret value must contain at least one byte");

        if (valueBytes.length > MAX_VALUE_BYTES)
            throw new InvalidSecretValueException("Secret value exceeds the 1 MiB UTF-8 limit");

        byte[] iv = randomBytes(IV_LENGTH);
        byte[] hashSalt = randomBytes(HASH_SALT_LENGTH);
        SecretKey key = symmetricKeyService.getKey();
        try {
            byte[] encryptedValue = encrypt(valueBytes, iv, key);
            byte[] valueHash = saltedHash(hashSalt, valueBytes);
            return new EncryptedSecretValue(
                    encryptedValue, iv, valueHash, hashSalt, CURRENT_ENCRYPTION_VERSION
            );
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Secret value could not be encrypted", exception);
        } finally {
            Arrays.fill(valueBytes, (byte) 0);
            ObfuscatedKeyMaterial.destroyRevealedKey(key);
        }
    }

    public EncryptedSecretValue encryptBinary(byte[] value) {
        if (value == null || value.length == 0)
            throw new InvalidSecretValueException("Binary secret value must contain at least one byte");
        
        return encryptBytes(value.clone());
    }

    public String decrypt(ApplicationSecret secret) {
        byte[] valueBytes = decryptAndVerify(secret);
        try {
            return new String(valueBytes, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(valueBytes, (byte) 0);
        }
    }

    public byte[] decryptBinary(app.alertify.jpa.entity.SecretBinaryValue value) {
        return decryptAndVerify(value.getEncryptedValue(), value.getEncryptionIv(), value.getValueHash(),
                value.getHashSalt(), value.getEncryptionVersion(), "binary secret", symmetricKeyService.getKey());
    }

    public boolean isRecoverable(ApplicationSecret secret) {
        byte[] valueBytes = null;
        try {
            valueBytes = decryptAndVerify(secret);
            return true;
        } catch (SecretNotRecoverableException exception) {
            return false;
        } finally {
            if (valueBytes != null)
                Arrays.fill(valueBytes, (byte) 0);
        }
    }

    private byte[] decryptAndVerify(ApplicationSecret secret) {
        return decryptAndVerify(secret.getEncryptedValue(), secret.getEncryptionIv(), secret.getValueHash(),
                secret.getHashSalt(), secret.getEncryptionVersion(), secret.getName(), symmetricKeyService.getKey());
    }

    byte[] decryptAndVerify(byte[] encrypted, byte[] iv, byte[] hash, byte[] salt, short version, String name, ObfuscatedKeyMaterial keyMaterial) {
        return decryptAndVerify(encrypted, iv, hash, salt, version, name, keyMaterial.revealKey());
    }

    EncryptedSecretValue encryptForKeyRotation(byte[] value, ObfuscatedKeyMaterial keyMaterial) {
        return encryptBytes(value.clone(), keyMaterial.revealKey());
    }

    private byte[] decryptAndVerify(byte[] encrypted, byte[] iv, byte[] hash, byte[] salt, short version, String name, SecretKey key) {
        if (version != CURRENT_ENCRYPTION_VERSION) {
            ObfuscatedKeyMaterial.destroyRevealedKey(key);
            throw new SecretNotRecoverableException(name);
        }

        byte[] decryptedValue = null;
        byte[] calculatedHash = null;
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            );
            decryptedValue = cipher.doFinal(encrypted);
            calculatedHash = saltedHash(salt, decryptedValue);
            if (!MessageDigest.isEqual(hash, calculatedHash)) {
                Arrays.fill(decryptedValue, (byte) 0);
                throw new SecretNotRecoverableException(name);
            }
            return decryptedValue;
        } catch (GeneralSecurityException | RuntimeException exception) {
            if (exception instanceof SecretNotRecoverableException notRecoverable)
                throw notRecoverable;

            if (decryptedValue != null)
                Arrays.fill(decryptedValue, (byte) 0);

            throw new SecretNotRecoverableException(name, exception);
        } finally {
            if (calculatedHash != null)
                Arrays.fill(calculatedHash, (byte) 0);

            ObfuscatedKeyMaterial.destroyRevealedKey(key);
        }
    }

    private EncryptedSecretValue encryptBytes(byte[] valueBytes) {
        return encryptBytes(valueBytes, symmetricKeyService.getKey());
    }

    private EncryptedSecretValue encryptBytes(byte[] valueBytes, SecretKey key) {
        byte[] iv = randomBytes(IV_LENGTH);
        byte[] hashSalt = randomBytes(HASH_SALT_LENGTH);
        try {
            return new EncryptedSecretValue(encrypt(valueBytes, iv, key), iv, saltedHash(hashSalt, valueBytes), hashSalt,
                    CURRENT_ENCRYPTION_VERSION);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Secret value could not be encrypted", exception);
        } finally {
            Arrays.fill(valueBytes, (byte) 0);
            ObfuscatedKeyMaterial.destroyRevealedKey(key);
        }
    }

    private static byte[] encrypt(byte[] value, byte[] iv, SecretKey key) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
        return cipher.doFinal(value);
    }

    private byte[] saltedHash(byte[] salt, byte[] value) {
        ByteBuffer input = ByteBuffer.allocate(Integer.BYTES + salt.length + value.length);
        input.putInt(salt.length);
        input.put(salt);
        input.put(value);
        byte[] encoded = input.array();
        try {
            return sha256HashService.hash(encoded);
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    private byte[] randomBytes(int length) {
        byte[] value = new byte[length];
        secureRandom.nextBytes(value);
        return value;
    }
}
