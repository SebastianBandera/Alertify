package app.alertify.services.secret;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

import app.alertify.jpa.repository.ApplicationSecretRepository;

class SecretKeyRotationStartupServiceTest {

    @Test
    void fastPathActivatesTheCurrentKeyWithoutLockingOrScanningSecrets() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        SymmetricKeyService keyService = mock(SymmetricKeyService.class);
        SecretEncryptionService encryptionService = mock(SecretEncryptionService.class);
        SecretKeyRotationTransactionService transactionService = mock(SecretKeyRotationTransactionService.class);
        ApplicationSecretRepository secretRepository = mock(ApplicationSecretRepository.class);
        ObfuscatedKeyMaterial oldKey = ObfuscatedKeyMaterial.from(new byte[32]);
        ObfuscatedKeyMaterial newKey = ObfuscatedKeyMaterial.from(new byte[32]);
        SymmetricKeyRotation rotation = new SymmetricKeyRotation(false, oldKey, newKey);
        when(keyService.readRotation()).thenReturn(rotation);
        SecretKeyRotationStartupService service = new SecretKeyRotationStartupService(
                dataSource, keyService, encryptionService, transactionService, secretRepository
        );

        service.initializeAndRotateIfRequired();

        verify(keyService).activate(newKey);
        verify(dataSource, never()).getConnection();
        verify(secretRepository, never()).findAllIds();
    }
}
