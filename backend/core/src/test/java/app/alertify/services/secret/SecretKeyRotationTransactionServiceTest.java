package app.alertify.services.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import app.alertify.jpa.entity.ApplicationSecret;
import app.alertify.jpa.entity.SecretBinaryValue;
import app.alertify.jpa.entity.SecretValueType;
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.jpa.repository.SecretBinaryValueRepository;

class SecretKeyRotationTransactionServiceTest {

    @Test
    void rotatesPlaceholderAndBinaryPayloadWithoutChangingValueRevision() {
        ObfuscatedKeyMaterial oldKey = material((byte) 1);
        ObfuscatedKeyMaterial newKey = material((byte) 2);
        try {
            SecretEncryptionService encryptionService = new SecretEncryptionService(mock(SymmetricKeyService.class), new Sha256HashService());
            EncryptedSecretValue placeholder = encryptionService.encryptForKeyRotation("BINARY".getBytes(StandardCharsets.UTF_8), oldKey);
            byte[] payload = new byte[] { 4, 5, 6, 7 };
            EncryptedSecretValue encryptedPayload = encryptionService.encryptForKeyRotation(payload, oldKey);
            ApplicationSecret secret = new ApplicationSecret(
                    "binary", null, SecretValueType.BINARY, placeholder.encryptedValue(), placeholder.encryptionIv(),
                    placeholder.valueHash(), placeholder.hashSalt(), placeholder.encryptionVersion(), Set.of(), false
            );
            SecretBinaryValue binary = new SecretBinaryValue(
                    7L, encryptedPayload.encryptedValue(), encryptedPayload.encryptionIv(), encryptedPayload.valueHash(),
                    encryptedPayload.hashSalt(), encryptedPayload.encryptionVersion()
            );
            ApplicationSecretRepository secretRepository = mock(ApplicationSecretRepository.class);
            SecretBinaryValueRepository binaryRepository = mock(SecretBinaryValueRepository.class);
            when(secretRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(secret));
            when(binaryRepository.findById(7L)).thenReturn(Optional.of(binary));
            SecretKeyRotationTransactionService service = new SecretKeyRotationTransactionService(secretRepository, binaryRepository, encryptionService);

            service.migrate(7L, oldKey, newKey);

            assertThat(secret.getValueRevision()).isEqualTo(1L);
            assertThat(decrypt(secret, encryptionService, newKey)).isEqualTo("BINARY".getBytes(StandardCharsets.UTF_8));
            assertThat(decrypt(binary, encryptionService, newKey)).isEqualTo(payload);
            assertThatThrownByDecrypt(secret, encryptionService, oldKey);
            assertThatThrownByDecrypt(binary, encryptionService, oldKey);
        } finally {
            oldKey.destroy();
            newKey.destroy();
        }
    }

    @Test
    void leavesAnAlreadyMigratedSecretUnchanged() {
        ObfuscatedKeyMaterial oldKey = material((byte) 1);
        ObfuscatedKeyMaterial newKey = material((byte) 2);
        try {
            SecretEncryptionService encryptionService = new SecretEncryptionService(mock(SymmetricKeyService.class), new Sha256HashService());
            EncryptedSecretValue encrypted = encryptionService.encryptForKeyRotation("value".getBytes(StandardCharsets.UTF_8), newKey);
            ApplicationSecret secret = new ApplicationSecret(
                    "already-new", null, encrypted.encryptedValue(), encrypted.encryptionIv(), encrypted.valueHash(),
                    encrypted.hashSalt(), encrypted.encryptionVersion(), Set.of()
            );
            byte[] originalCiphertext = secret.getEncryptedValue();
            ApplicationSecretRepository secretRepository = mock(ApplicationSecretRepository.class);
            SecretBinaryValueRepository binaryRepository = mock(SecretBinaryValueRepository.class);
            when(secretRepository.findByIdForUpdate(8L)).thenReturn(Optional.of(secret));
            when(binaryRepository.findById(8L)).thenReturn(Optional.empty());
            SecretKeyRotationTransactionService service = new SecretKeyRotationTransactionService(secretRepository, binaryRepository, encryptionService);

            service.migrate(8L, oldKey, newKey);

            assertThat(secret.getEncryptedValue()).containsExactly(originalCiphertext);
            assertThat(secret.getValueRevision()).isEqualTo(1L);
        } finally {
            oldKey.destroy();
            newKey.destroy();
        }
    }

    private static ObfuscatedKeyMaterial material(byte value) {
        byte[] key = new byte[32];
        Arrays.fill(key, value);
        try {
            return ObfuscatedKeyMaterial.from(key);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private static byte[] decrypt(ApplicationSecret secret, SecretEncryptionService service, ObfuscatedKeyMaterial key) {
        return service.decryptAndVerify(secret.getEncryptedValue(), secret.getEncryptionIv(), secret.getValueHash(), secret.getHashSalt(), secret.getEncryptionVersion(), secret.getName(), key);
    }

    private static byte[] decrypt(SecretBinaryValue binary, SecretEncryptionService service, ObfuscatedKeyMaterial key) {
        return service.decryptAndVerify(binary.getEncryptedValue(), binary.getEncryptionIv(), binary.getValueHash(), binary.getHashSalt(), binary.getEncryptionVersion(), "binary", key);
    }

    private static void assertThatThrownByDecrypt(ApplicationSecret secret, SecretEncryptionService service, ObfuscatedKeyMaterial key) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> decrypt(secret, service, key)).isInstanceOf(SecretNotRecoverableException.class);
    }

    private static void assertThatThrownByDecrypt(SecretBinaryValue binary, SecretEncryptionService service, ObfuscatedKeyMaterial key) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> decrypt(binary, service, key)).isInstanceOf(SecretNotRecoverableException.class);
    }
}
