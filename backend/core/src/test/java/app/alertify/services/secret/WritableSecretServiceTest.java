package app.alertify.services.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import app.alertify.jpa.entity.ApplicationSecret;
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.worker.grpc.WritableSecretValue;

@ExtendWith(MockitoExtension.class)
class WritableSecretServiceTest {

    @Mock private ApplicationSecretRepository secretRepository;
    @Mock private SecretEncryptionService encryptionService;
    @Mock private ApplicationEventLogger eventLogger;

    @Test
    void encryptsAndPersistsChangedValueWithoutLoggingIt() {
        ApplicationSecret secret = secret(true);
        EncryptedSecretValue encrypted = encrypted("new-cipher");
        when(secretRepository.findById(10L)).thenReturn(Optional.of(secret));
        when(encryptionService.encrypt("rotated-value")).thenReturn(encrypted);
        UUID executionId = UUID.randomUUID();

        service().apply(20L, "Token rotation", executionId, Set.of(result("rotated-value")));

        assertThat(secret.getEncryptedValue()).isEqualTo(encrypted.encryptedValue());
        assertThat(secret.getValueRevision()).isEqualTo(2);
        verify(secretRepository).flush();
        verify(eventLogger).successAfterCommit(
                eq("SECRET_OVERWRITTEN_BY_ALERT"),
                org.mockito.ArgumentMatchers.argThat(data ->
                    data.get("secretName").equals("api.token")
                        && data.get("alertName").equals("Token rotation")
                        && data.get("executionId").equals(executionId)
                        && data.get("parameterName").equals("token")
                        && !data.containsKey("value")
                )
        );
    }

    @Test
    void ignoresWorkerValueWhenSecretIsNoLongerWritable() {
        ApplicationSecret secret = secret(false);
        when(secretRepository.findById(10L)).thenReturn(Optional.of(secret));

        service().apply(20L, "Token rotation", UUID.randomUUID(), Set.of(result("rotated-value")));

        assertThat(secret.getValueRevision()).isEqualTo(1);
        verify(encryptionService, never()).encrypt("rotated-value");
        verify(secretRepository, never()).flush();
        verify(eventLogger, never()).successAfterCommit(eq("SECRET_OVERWRITTEN_BY_ALERT"), anyMap());
    }

    @Test
    void rejectsNullValueWithoutFailingTheCaller() {
        ApplicationSecret secret = secret(true);
        when(secretRepository.findById(10L)).thenReturn(Optional.of(secret));
        WritableSecretValue result = WritableSecretValue.newBuilder()
                .setSecretId(10L)
                .setParameterName("token")
                .setNullValue(true)
                .build();

        service().apply(20L, "Token rotation", UUID.randomUUID(), Set.of(result));

        assertThat(secret.getValueRevision()).isEqualTo(1);
        verify(encryptionService, never()).encrypt(org.mockito.ArgumentMatchers.any());
        verify(secretRepository, never()).flush();
        verify(eventLogger).errorAfterCommit(eq("SECRET_OVERWRITE_REJECTED"), anyMap());
    }

    private WritableSecretService service() {
        return new WritableSecretService(secretRepository, encryptionService, eventLogger);
    }

    private static ApplicationSecret secret(boolean writable) {
        ApplicationSecret secret = new ApplicationSecret(
                "api.token", null, "old-cipher-value".getBytes(StandardCharsets.UTF_8),
                new byte[12], new byte[32], new byte[16], (short) 1, Set.of(), writable
        );
        ReflectionTestUtils.setField(secret, "id", 10L);
        return secret;
    }

    private static EncryptedSecretValue encrypted(String value) {
        return new EncryptedSecretValue(
                value.getBytes(StandardCharsets.UTF_8), new byte[12], new byte[32],
                new byte[16], (short) 1
        );
    }

    private static WritableSecretValue result(String value) {
        return WritableSecretValue.newBuilder()
                .setSecretId(10L)
                .setParameterName("token")
                .setValue(value)
                .build();
    }
}
