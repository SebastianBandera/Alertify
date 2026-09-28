package app.alertify.services.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.jpa.entity.ApplicationSecret;
import app.alertify.jpa.entity.SecretBinaryValue;
import app.alertify.jpa.entity.SecretValueType;
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.jpa.repository.SecretBinaryValueRepository;
import app.alertify.logging.ApplicationEventLogger;

@ExtendWith(MockitoExtension.class)
class SecretAccessServiceTest {

    @Mock private ApplicationSecretRepository secretRepository;
    @Mock private SecretBinaryValueRepository binaryValueRepository;
    @Mock private SecretExpressionService expressionService;
    @Mock private SecretEncryptionService encryptionService;
    @Mock private ApplicationEventLogger eventLogger;
    @Mock private ApplicationSecret secret;
    private SecretAccessService service;

    @BeforeEach
    void setUp() {
        service = new SecretAccessService(secretRepository, binaryValueRepository, expressionService, encryptionService, eventLogger);
    }

    @Test
    void logsTheDirectConsumerWithoutTheResolvedValue() {
        SecretAccessContext context = SecretAccessContext.alert(12L, "Verify PostgreSQL");
        secret("Database credentials", SecretValueType.DB_SECRET);
        when(secretRepository.findByNameIgnoreCase("Database credentials")).thenReturn(Optional.of(secret));
        when(expressionService.resolve(secret, context)).thenReturn("plaintext-secret");

        String value = service.getValueByName("Database credentials", context);

        assertThat(value).isEqualTo("plaintext-secret");
        verify(eventLogger).success(eq("SECRET_VALUE_ACCESSED"), org.mockito.ArgumentMatchers.argThat(data ->
                data.get("secretId").equals(4L)
                        && data.get("name").equals("Database credentials")
                        && data.get("valueType").equals("DB_SECRET")
                        && data.get("consumerType").equals("ALERT")
                        && data.get("consumerId").equals(12L)
                        && data.get("consumerName").equals("Verify PostgreSQL")
                        && !data.containsValue("plaintext-secret")));
    }

    @Test
    void includesTheConsumerWhenTheSecretDoesNotExist() {
        SecretAccessContext context = SecretAccessContext.procedure(23L, "Deploy database");
        when(secretRepository.findByNameIgnoreCase("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getValueByName("missing", context)).isInstanceOf(ResourceNotFoundException.class);

        verify(eventLogger).failure(eq("SECRET_VALUE_ACCESSED"), org.mockito.ArgumentMatchers.argThat(data ->
                data.get("name").equals("missing")
                        && data.get("reason").equals("NOT_FOUND")
                        && data.get("consumerType").equals("PROCEDURE")
                        && data.get("consumerId").equals(23L)
                        && data.get("consumerName").equals("Deploy database")));
    }

    @Test
    void auditsBinarySecretAccessWithTheSameConsumerShape() {
        SecretAccessContext context = SecretAccessContext.alert(31L, "Upload certificate");
        SecretBinaryValue binaryValue = org.mockito.Mockito.mock(SecretBinaryValue.class);
        byte[] plaintext = { 1, 2, 3 };
        secret("Client certificate", SecretValueType.BINARY);
        when(binaryValueRepository.findById(4L)).thenReturn(Optional.of(binaryValue));
        when(encryptionService.decryptBinary(binaryValue)).thenReturn(plaintext);

        byte[] value = service.getBinaryValue(secret, context);

        assertThat(value).isSameAs(plaintext);
        verify(eventLogger).success(eq("SECRET_VALUE_ACCESSED"), org.mockito.ArgumentMatchers.argThat(data ->
                data.get("secretId").equals(4L)
                        && data.get("valueType").equals("BINARY")
                        && data.get("consumerType").equals("ALERT")
                        && data.get("consumerId").equals(31L)));
    }

    private void secret(String name, SecretValueType valueType) {
        when(secret.getId()).thenReturn(4L);
        when(secret.getName()).thenReturn(name);
        when(secret.getValueType()).thenReturn(valueType);
    }
}
