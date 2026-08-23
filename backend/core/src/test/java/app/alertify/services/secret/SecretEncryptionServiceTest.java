package app.alertify.services.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

import app.alertify.api.error.InvalidSecretValueException;
import app.alertify.jpa.entity.ApplicationSecret;

class SecretEncryptionServiceTest {

    @Test
    void encryptsWithRandomIvAndDecryptsOnlyWithTheCurrentKey() {
        SecretEncryptionService service = serviceWithKey((byte) 7);
        EncryptedSecretValue first = service.encrypt("super-secret-value");
        EncryptedSecretValue second = service.encrypt("super-secret-value");
        ApplicationSecret secret = secret(first);

        assertThat(first.encryptedValue()).isNotEqualTo(second.encryptedValue());
        assertThat(first.encryptionIv()).isNotEqualTo(second.encryptionIv());
        assertThat(first.hashSalt()).isNotEqualTo(second.hashSalt());
        assertThat(service.decrypt(secret)).isEqualTo("super-secret-value");
        assertThat(service.isRecoverable(secret)).isTrue();
        assertThat(serviceWithKey((byte) 8).isRecoverable(secret)).isFalse();
        assertThatThrownBy(() -> serviceWithKey((byte) 8).decrypt(secret)).isInstanceOf(SecretNotRecoverableException.class);
    }

    @Test
    void rejectsCiphertextWhoseIndependentHashDoesNotMatch() {
        SecretEncryptionService service = serviceWithKey((byte) 7);
        EncryptedSecretValue encrypted = service.encrypt("super-secret-value");
        byte[] changedHash = encrypted.valueHash();
        changedHash[0] ^= 1;
        ApplicationSecret secret = new ApplicationSecret(
                "api.token", null, encrypted.encryptedValue(), encrypted.encryptionIv(), changedHash,
                encrypted.hashSalt(), encrypted.encryptionVersion(), Set.of()
        );

        assertThat(service.isRecoverable(secret)).isFalse();
        assertThatThrownBy(() -> service.decrypt(secret)).isInstanceOf(SecretNotRecoverableException.class);
    }

    @Test
    void enforcesOneMebibyteUtf8Limit() {
        SecretEncryptionService service = serviceWithKey((byte) 7);
        String accepted = "a".repeat(SecretEncryptionService.MAX_VALUE_BYTES);
        String rejected = accepted + "á";

        assertThat(service.decrypt(secret(service.encrypt(accepted)))).hasSize(accepted.length());
        assertThatThrownBy(() -> service.encrypt(rejected)).isInstanceOf(InvalidSecretValueException.class);
    }

    private static SecretEncryptionService serviceWithKey(byte value) {
        SymmetricKeyService keyService = mock(SymmetricKeyService.class);
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, value);
        when(keyService.getKey()).thenReturn(new SecretKeySpec(key, "AES"));
        return new SecretEncryptionService(keyService, new Sha256HashService());
    }

    private static ApplicationSecret secret(EncryptedSecretValue encrypted) {
        return new ApplicationSecret(
                "api.token", "API token", encrypted.encryptedValue(), encrypted.encryptionIv(),
                encrypted.valueHash(), encrypted.hashSalt(), encrypted.encryptionVersion(), Set.of()
        );
    }
}
