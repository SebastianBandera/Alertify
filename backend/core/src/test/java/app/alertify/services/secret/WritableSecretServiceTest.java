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
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import app.alertify.configuration.service.ConfigurationExpressionParser;
import app.alertify.jpa.entity.ApplicationSecret;
import app.alertify.jpa.entity.SecretValueType;
import app.alertify.jpa.entity.SecretBinaryValue;
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.jpa.repository.SecretBinaryValueRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.worker.contract.BinaryPayloadCodec;
import app.alertify.worker.grpc.WritableSecretValue;

@ExtendWith(MockitoExtension.class)
class WritableSecretServiceTest {

    @Mock private ApplicationSecretRepository secretRepository;
    @Mock private SecretEncryptionService encryptionService;
    @Mock private ApplicationEventLogger eventLogger;
    @Mock private SecretBinaryValueRepository binaryRepository;

    @Test
    void encryptsAndPersistsChangedValueWithoutLoggingIt() {
        ApplicationSecret secret = secret(true);
        EncryptedSecretValue encrypted = encrypted("new-cipher");
        when(secretRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(secret));
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
        when(secretRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(secret));

        service().apply(20L, "Token rotation", UUID.randomUUID(), Set.of(result("rotated-value")));

        assertThat(secret.getValueRevision()).isEqualTo(1);
        verify(encryptionService, never()).encrypt("rotated-value");
        verify(secretRepository, never()).flush();
        verify(eventLogger, never()).successAfterCommit(eq("SECRET_OVERWRITTEN_BY_ALERT"), anyMap());
    }

    @Test
    void rejectsWriteBackWhenTheSecretChangedAfterPreparation() {
        ApplicationSecret secret = secret(true);
        ReflectionTestUtils.setField(secret, "version", 2L);
        when(secretRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(secret));
        WritableSecretValue stale = result("rotated-value").toBuilder().setExpectedVersion(1L).build();

        service().apply(20L, "Token rotation", UUID.randomUUID(), Set.of(stale));

        assertThat(secret.getValueRevision()).isEqualTo(1);
        verify(encryptionService, never()).encrypt("rotated-value");
        verify(secretRepository, never()).flush();
        verify(eventLogger).errorAfterCommit(
                eq("SECRET_OVERWRITE_REJECTED"),
                org.mockito.ArgumentMatchers.argThat(data -> data.get("reason").toString().contains("changed after execution preparation"))
        );
    }

    @Test
    void rejectsNullValueWithoutFailingTheCaller() {
        ApplicationSecret secret = secret(true);
        when(secretRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(secret));
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

    @Test
    void normalizesDatabaseSecretsBeforeEncryptingThem() {
        ApplicationSecret secret = secret(SecretValueType.DB_SECRET, true);
        String canonical = "{\"engine\":\"POSTGRESQL\",\"host\":\"db\",\"port\":5432,\"database\":\"app\","
                + "\"username\":\"u\",\"password\":\"p\",\"options\":null}";
        EncryptedSecretValue encrypted = encrypted("db-cipher");
        when(secretRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(secret));
        when(encryptionService.encrypt(canonical)).thenReturn(encrypted);

        service().apply(20L, "Rotation", UUID.randomUUID(), Set.of(result(
                "{\"password\":\"p\",\"username\":\"u\",\"database\":\"app\",\"port\":5432,\"host\":\" db \",\"engine\":\"POSTGRESQL\"}")));

        assertThat(secret.getEncryptedValue()).isEqualTo(encrypted.encryptedValue());
        assertThat(secret.getValueRevision()).isEqualTo(2);
        verify(eventLogger).successAfterCommit(eq("SECRET_OVERWRITTEN_BY_ALERT"), anyMap());
    }

    @Test
    void rejectsWorkerValuesThatDoNotMatchTheDatabaseSecretShape() {
        ApplicationSecret secret = secret(SecretValueType.DB_SECRET, true);
        when(secretRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(secret));

        service().apply(20L, "Rotation", UUID.randomUUID(), Set.of(result("just-a-string")));

        assertThat(secret.getValueRevision()).isEqualTo(1);
        verify(encryptionService, never()).encrypt(org.mockito.ArgumentMatchers.any());
        verify(secretRepository, never()).flush();
        verify(eventLogger).errorAfterCommit(
                eq("SECRET_OVERWRITE_REJECTED"),
                org.mockito.ArgumentMatchers.argThat(data -> data.get("reason").toString().contains("DB_SECRET"))
        );
    }

    @Test
    void recompressesEncryptsAndRevisesWritableBinaryBytes() {
        byte[] changed = new byte[] { 9, 8, 7, 6 };
        ApplicationSecret secret = secret(SecretValueType.BINARY, true);
        secret.changeBinaryMetadata("private.sqlite", "application/vnd.sqlite3", 1, 1);
        EncryptedSecretValue encrypted = encrypted("binary-cipher");
        EncryptedSecretValue placeholder = encrypted("binary-marker");
        when(secretRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(secret));
        when(encryptionService.encryptBinary(org.mockito.ArgumentMatchers.any(byte[].class))).thenReturn(encrypted);
        when(encryptionService.encrypt("BINARY")).thenReturn(placeholder);
        WritableSecretValue value = WritableSecretValue.newBuilder()
                .setSecretId(10L).setParameterName("database")
                .setBinaryValue(com.google.protobuf.ByteString.copyFrom(BinaryPayloadCodec.compress(changed, 104857600)))
                .build();

        service().apply(20L, "SQLite updater", UUID.randomUUID(), Set.of(value));

        ArgumentCaptor<byte[]> zipped = ArgumentCaptor.forClass(byte[].class);
        verify(encryptionService).encryptBinary(zipped.capture());
        assertThat(BinaryPayloadCodec.decompress(zipped.getValue(), 104857600)).isEqualTo(changed);
        ArgumentCaptor<SecretBinaryValue> persisted = ArgumentCaptor.forClass(SecretBinaryValue.class);
        verify(binaryRepository).save(persisted.capture());
        assertThat(persisted.getValue().getEncryptedValue()).isEqualTo(encrypted.encryptedValue());
        assertThat(secret.getEncryptedValue()).isEqualTo(placeholder.encryptedValue());
        assertThat(secret.getValueRevision()).isEqualTo(2);
        verify(secretRepository).flush();
    }

    @Test
    void encryptsAndRevisesEmptyWritableBinaryBytes() {
        ApplicationSecret secret = secret(SecretValueType.BINARY, true);
        secret.changeBinaryMetadata("private.sqlite", "application/vnd.sqlite3", 4, 1);
        EncryptedSecretValue encrypted = encrypted("binary-cipher");
        EncryptedSecretValue placeholder = encrypted("binary-marker");
        when(secretRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(secret));
        when(encryptionService.encryptBinary(org.mockito.ArgumentMatchers.any(byte[].class))).thenReturn(encrypted);
        when(encryptionService.encrypt("BINARY")).thenReturn(placeholder);
        WritableSecretValue value = WritableSecretValue.newBuilder()
                .setSecretId(10L).setParameterName("database")
                .setBinaryValue(com.google.protobuf.ByteString.copyFrom(BinaryPayloadCodec.compress(new byte[0], 104857600)))
                .build();

        service().apply(20L, "SQLite updater", UUID.randomUUID(), Set.of(value));

        ArgumentCaptor<byte[]> zipped = ArgumentCaptor.forClass(byte[].class);
        verify(encryptionService).encryptBinary(zipped.capture());
        assertThat(BinaryPayloadCodec.decompress(zipped.getValue(), 104857600)).isEmpty();
        verify(binaryRepository).save(org.mockito.ArgumentMatchers.any(SecretBinaryValue.class));
        assertThat(secret.getBinarySize()).isZero();
        assertThat(secret.getValueRevision()).isEqualTo(2);
        verify(secretRepository).flush();
    }

    private WritableSecretService service() {
        return new WritableSecretService(secretRepository, encryptionService, new SecretValueValidator(new ConfigurationExpressionParser()), eventLogger,
                binaryRepository,
                new app.alertify.binary.BinaryPayloadService(104857600));
    }

    private static ApplicationSecret secret(boolean writable) {
        return secret(SecretValueType.STRING, writable);
    }

    private static ApplicationSecret secret(SecretValueType valueType, boolean writable) {
        ApplicationSecret secret = new ApplicationSecret(
                "api.token", null, valueType, "old-cipher-value".getBytes(StandardCharsets.UTF_8),
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
