package app.alertify.services.secret;

import java.util.Arrays;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import app.alertify.jpa.entity.ApplicationSecret;
import app.alertify.jpa.entity.SecretBinaryValue;
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.jpa.repository.SecretBinaryValueRepository;

/** Migrates one logical secret atomically without changing its value revision. */
@Service
class SecretKeyRotationTransactionService {

    private final ApplicationSecretRepository secretRepository;
    private final SecretBinaryValueRepository binaryRepository;
    private final SecretEncryptionService encryptionService;

    SecretKeyRotationTransactionService(ApplicationSecretRepository secretRepository, SecretBinaryValueRepository binaryRepository, SecretEncryptionService encryptionService) {
        this.secretRepository = secretRepository;
        this.binaryRepository = binaryRepository;
        this.encryptionService = encryptionService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void migrate(Long secretId, ObfuscatedKeyMaterial oldKey, ObfuscatedKeyMaterial newKey) {
        ApplicationSecret secret = secretRepository.findByIdForUpdate(secretId)
                .orElseThrow(() -> new IllegalStateException("Secret " + secretId + " disappeared during key rotation"));
        rotateSecretValue(secret, oldKey, newKey);
        binaryRepository.findById(secretId).ifPresent(binary -> rotateBinaryValue(binary, oldKey, newKey));
    }

    private void rotateSecretValue(ApplicationSecret secret, ObfuscatedKeyMaterial oldKey, ObfuscatedKeyMaterial newKey) {
        byte[] plaintext = decryptOldOrConfirmNew(
                secret.getEncryptedValue(), secret.getEncryptionIv(), secret.getValueHash(), secret.getHashSalt(),
                secret.getEncryptionVersion(), "secret " + secret.getId(), oldKey, newKey
        );
        if (plaintext == null)
            return;

        try {
            EncryptedSecretValue encrypted = encryptionService.encryptForKeyRotation(plaintext, newKey);
            secret.rotateEncryptedValue(encrypted.encryptedValue(), encrypted.encryptionIv(), encrypted.valueHash(), encrypted.hashSalt(), encrypted.encryptionVersion());
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private void rotateBinaryValue(SecretBinaryValue binary, ObfuscatedKeyMaterial oldKey, ObfuscatedKeyMaterial newKey) {
        byte[] plaintext = decryptOldOrConfirmNew(
                binary.getEncryptedValue(), binary.getEncryptionIv(), binary.getValueHash(), binary.getHashSalt(),
                binary.getEncryptionVersion(), "binary secret " + binary.getSecretId(), oldKey, newKey
        );
        if (plaintext == null)
            return;

        try {
            EncryptedSecretValue encrypted = encryptionService.encryptForKeyRotation(plaintext, newKey);
            binary.replace(encrypted.encryptedValue(), encrypted.encryptionIv(), encrypted.valueHash(), encrypted.hashSalt(), encrypted.encryptionVersion());
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private byte[] decryptOldOrConfirmNew(byte[] encrypted, byte[] iv, byte[] hash, byte[] salt, short version, String name, ObfuscatedKeyMaterial oldKey, ObfuscatedKeyMaterial newKey) {
        try {
            return encryptionService.decryptAndVerify(encrypted, iv, hash, salt, version, name, oldKey);
        } catch (SecretNotRecoverableException oldKeyFailure) {
            byte[] plaintext = encryptionService.decryptAndVerify(encrypted, iv, hash, salt, version, name, newKey);
            Arrays.fill(plaintext, (byte) 0);
            return null;
        }
    }
}
