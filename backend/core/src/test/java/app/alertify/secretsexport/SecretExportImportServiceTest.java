package app.alertify.secretsexport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import app.alertify.binary.BinaryPayloadService;
import app.alertify.jpa.entity.ApplicationSecret;
import app.alertify.jpa.entity.SecretBinaryValue;
import app.alertify.jpa.entity.SecretValueType;
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.jpa.repository.SecretBinaryValueRepository;
import app.alertify.jpa.repository.SystemConfigurationRepository;
import app.alertify.jpa.repository.TagRepository;
import app.alertify.services.secret.SecretEncryptionService;
import app.alertify.services.secret.SecretExpressionDependencySynchronizer;
import app.alertify.worker.contract.BinaryPayloadCodec;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.enums.CompressionMethod;
import tools.jackson.databind.json.JsonMapper;

class SecretExportImportServiceTest {

    @Test
    void encryptsBinaryZipWithoutCompressingItAgain(@TempDir Path directory) throws Exception {
        ApplicationSecretRepository secretRepository = mock(ApplicationSecretRepository.class);
        SystemConfigurationRepository systemConfigurationRepository = mock(SystemConfigurationRepository.class);
        TagRepository tagRepository = mock(TagRepository.class);
        SecretEncryptionService encryptionService = mock(SecretEncryptionService.class);
        SecretExpressionDependencySynchronizer dependencySynchronizer = mock(SecretExpressionDependencySynchronizer.class);
        SecretBinaryValueRepository binaryRepository = mock(SecretBinaryValueRepository.class);

        ApplicationSecret secret = new ApplicationSecret(
                "database.file", null, SecretValueType.BINARY, new byte[] { 1 }, new byte[12], new byte[32],
                new byte[16], (short) 1, Set.of(), false
        );
        ReflectionTestUtils.setField(secret, "id", 42L);
        byte[] logicalBytes = new byte[8192];
        byte[] storedZip = BinaryPayloadCodec.compress(logicalBytes, 104857600);
        secret.changeBinaryMetadata("database.sqlite", "application/vnd.sqlite3", logicalBytes.length, storedZip.length);
        SecretBinaryValue binaryValue = new SecretBinaryValue(42L, new byte[] { 2 }, new byte[12], new byte[32], new byte[16], (short) 1);

        when(secretRepository.findAll()).thenReturn(List.of(secret));
        when(systemConfigurationRepository.findAll()).thenReturn(List.of());
        when(binaryRepository.findById(42L)).thenReturn(Optional.of(binaryValue));
        when(encryptionService.decryptBinary(binaryValue)).thenReturn(storedZip.clone());

        SecretExportImportService service = new SecretExportImportService(
                secretRepository, systemConfigurationRepository, tagRepository, encryptionService,
                dependencySynchronizer, JsonMapper.builder().build(), binaryRepository,
                new BinaryPayloadService(104857600)
        );

        SecretExportImportService.ExportResult result = service.export(directory);

        try (ZipFile archive = new ZipFile(result.file().toFile(), result.password().toCharArray())) {
            var header = archive.getFileHeader("binary-secrets/42.zip");
            assertThat(header).isNotNull();
            assertThat(header.isEncrypted()).isTrue();
            assertThat(header.getAesExtraDataRecord().getCompressionMethod()).isEqualTo(CompressionMethod.STORE);
            assertThat(archive.getInputStream(header).readAllBytes()).isEqualTo(storedZip);
        }
    }
}
