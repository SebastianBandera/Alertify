package app.alertify.services.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import app.alertify.api.error.ConflictException;
import app.alertify.binary.BinaryPayloadService;
import app.alertify.jpa.entity.ApplicationSecret;
import app.alertify.jpa.entity.SecretValueType;
import app.alertify.jpa.entity.Tag;
import app.alertify.jpa.entity.TagScope;
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.jpa.repository.SecretBinaryValueRepository;
import app.alertify.jpa.repository.TagRepository;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.secret.api.SecretMetadataUpdateRequest;

@ExtendWith(MockitoExtension.class)
class ApplicationSecretMetadataUpdateTest {

    @Mock private ApplicationSecretRepository secretRepository;
    @Mock private TagRepository tagRepository;
    @Mock private SecretEncryptionService encryptionService;
    @Mock private SecretValueValidator valueValidator;
    @Mock private SecretExpressionService expressionService;
    @Mock private SecretMapper mapper;
    @Mock private ApplicationEventLogger eventLogger;
    @Mock private SecretBinaryValueRepository binaryRepository;
    @Mock private BinaryPayloadService binaryPayloadService;

    @Test
    void updatesMetadataWithoutChangingEncryptedValueOrRevision() {
        ApplicationSecret secret = secret(SecretValueType.STRING);
        byte[] encryptedValue = secret.getEncryptedValue();
        byte[] iv = secret.getEncryptionIv();
        byte[] hash = secret.getValueHash();
        byte[] salt = secret.getHashSalt();
        Tag tag = tag(7L);
        when(secretRepository.findById(10L)).thenReturn(Optional.of(secret));
        when(tagRepository.findAllByIdInAndScope(Set.of(7L), TagScope.SECRET)).thenReturn(List.of(tag));

        service().updateMetadata(10L, new SecretMetadataUpdateRequest(3L, " renamed ", " description ", Set.of(7L), true));

        assertThat(secret.getName()).isEqualTo("renamed");
        assertThat(secret.getDescription()).isEqualTo("description");
        assertThat(secret.getTags()).containsExactly(tag);
        assertThat(secret.isWritable()).isTrue();
        assertThat(secret.getValueType()).isEqualTo(SecretValueType.STRING);
        assertThat(secret.getEncryptedValue()).isEqualTo(encryptedValue);
        assertThat(secret.getEncryptionIv()).isEqualTo(iv);
        assertThat(secret.getValueHash()).isEqualTo(hash);
        assertThat(secret.getHashSalt()).isEqualTo(salt);
        assertThat(secret.getValueRevision()).isEqualTo(1);
        verify(secretRepository).flush();
        verify(expressionService).ensureNotReferenced(secret, "renamed");
        verify(expressionService, never()).synchronizeDependencies(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(eventLogger).successAfterCommit(eq("SECRET_UPDATED"), org.mockito.ArgumentMatchers.argThat(data ->
                data.get("changedFields").equals(Set.of("name", "description", "tags", "writable"))
                        && data.get("valueRevision").equals(1L)
                        && data.get("previousName").equals("original")));
        verifyNoInteractions(encryptionService, valueValidator, binaryRepository, binaryPayloadService);
    }

    @Test
    void updatesBinaryDescriptionWithoutTouchingBinaryPayload() {
        ApplicationSecret secret = secret(SecretValueType.BINARY);
        secret.changeBinaryMetadata("private.db", "application/octet-stream", 32, 42);
        byte[] encryptedValue = secret.getEncryptedValue();
        when(secretRepository.findById(10L)).thenReturn(Optional.of(secret));

        service().updateMetadata(10L, new SecretMetadataUpdateRequest(3L, "original", "new description", Set.of(), false));

        assertThat(secret.getDescription()).isEqualTo("new description");
        assertThat(secret.getValueType()).isEqualTo(SecretValueType.BINARY);
        assertThat(secret.getEncryptedValue()).isEqualTo(encryptedValue);
        assertThat(secret.getBinaryFileName()).isEqualTo("private.db");
        assertThat(secret.getBinaryContentType()).isEqualTo("application/octet-stream");
        assertThat(secret.getBinarySize()).isEqualTo(32L);
        assertThat(secret.getBinaryZipSize()).isEqualTo(42L);
        assertThat(secret.getValueRevision()).isEqualTo(1);
        verify(secretRepository).flush();
        verify(eventLogger).successAfterCommit(eq("SECRET_UPDATED"), org.mockito.ArgumentMatchers.argThat(data ->
                data.get("changedFields").equals(Set.of("description"))));
        verifyNoInteractions(encryptionService, valueValidator, binaryRepository, binaryPayloadService);
    }

    @Test
    void changesDescriptionOfReferencedSecretWithoutCheckingRename() {
        ApplicationSecret secret = secret(SecretValueType.EXPRESSION);
        when(secretRepository.findById(10L)).thenReturn(Optional.of(secret));
        lenient().doThrow(new ConflictException("referenced")).when(expressionService).ensureNotReferenced(secret, "renamed");

        service().updateMetadata(10L, new SecretMetadataUpdateRequest(3L, "original", "updated", Set.of(), false));

        assertThat(secret.getDescription()).isEqualTo("updated");
        assertThat(secret.getValueRevision()).isEqualTo(1);
        verify(expressionService, never()).ensureNotReferenced(secret, "renamed");
        verify(secretRepository).flush();
        verify(eventLogger).successAfterCommit(eq("SECRET_UPDATED"), org.mockito.ArgumentMatchers.argThat(data ->
                data.get("changedFields").equals(Set.of("description"))));
    }

    @Test
    void tagsOnlyUpdateFlushesAndAuditsWithoutRevisingValue() {
        ApplicationSecret secret = secret(SecretValueType.STRING);
        Tag tag = tag(7L);
        when(secretRepository.findById(10L)).thenReturn(Optional.of(secret));
        when(tagRepository.findAllByIdInAndScope(Set.of(7L), TagScope.SECRET)).thenReturn(List.of(tag));

        service().updateMetadata(10L, new SecretMetadataUpdateRequest(3L, "original", null, Set.of(7L), false));

        assertThat(secret.getTags()).containsExactly(tag);
        assertThat(secret.getValueRevision()).isEqualTo(1);
        verify(secretRepository).flush();
        verify(eventLogger).successAfterCommit(eq("SECRET_UPDATED"), org.mockito.ArgumentMatchers.argThat(data ->
                data.get("changedFields").equals(Set.of("tags"))));
        verifyNoInteractions(encryptionService, binaryRepository);
    }

    @Test
    void unchangedMetadataDoesNotFlushOrAudit() {
        ApplicationSecret secret = secret(SecretValueType.STRING);
        when(secretRepository.findById(10L)).thenReturn(Optional.of(secret));

        service().updateMetadata(10L, new SecretMetadataUpdateRequest(3L, " original ", " ", Set.of(), false));

        assertThat(secret.getVersion()).isEqualTo(3);
        assertThat(secret.getValueRevision()).isEqualTo(1);
        verify(secretRepository, never()).flush();
        verify(eventLogger, never()).successAfterCommit(eq("SECRET_UPDATED"), anyMap());
        verifyNoInteractions(encryptionService, valueValidator, expressionService, binaryRepository, binaryPayloadService);
    }

    @Test
    void rejectsStaleVersionBeforeApplyingMetadata() {
        ApplicationSecret secret = secret(SecretValueType.STRING);
        when(secretRepository.findById(10L)).thenReturn(Optional.of(secret));

        assertThatThrownBy(() -> service().updateMetadata(10L, new SecretMetadataUpdateRequest(2L, "renamed", null, Set.of(), false)))
                .isInstanceOf(ConflictException.class);

        assertThat(secret.getName()).isEqualTo("original");
        verify(secretRepository, never()).flush();
        verifyNoInteractions(tagRepository, expressionService, eventLogger);
    }

    @Test
    void rejectsDuplicateNameAndReferencedRename() {
        ApplicationSecret secret = secret(SecretValueType.STRING);
        when(secretRepository.findById(10L)).thenReturn(Optional.of(secret));
        when(secretRepository.existsByNameIgnoreCaseAndIdNot("renamed", 10L)).thenReturn(true);

        assertThatThrownBy(() -> service().updateMetadata(10L, new SecretMetadataUpdateRequest(3L, "renamed", null, Set.of(), false)))
                .isInstanceOf(ConflictException.class);

        when(secretRepository.existsByNameIgnoreCaseAndIdNot("renamed", 10L)).thenReturn(false);
        doThrow(new ConflictException("referenced")).when(expressionService).ensureNotReferenced(secret, "renamed");
        assertThatThrownBy(() -> service().updateMetadata(10L, new SecretMetadataUpdateRequest(3L, "renamed", null, Set.of(), false)))
                .isInstanceOf(ConflictException.class);

        assertThat(secret.getName()).isEqualTo("original");
        verify(secretRepository, never()).flush();
        verify(eventLogger, never()).successAfterCommit(eq("SECRET_UPDATED"), anyMap());
    }

    private ApplicationSecretService service() {
        return new ApplicationSecretService(secretRepository, tagRepository, encryptionService, valueValidator, expressionService,
                mapper, eventLogger, binaryRepository, binaryPayloadService);
    }

    private static ApplicationSecret secret(SecretValueType type) {
        ApplicationSecret secret = new ApplicationSecret("original", null, type, "cipher".getBytes(StandardCharsets.UTF_8),
                new byte[12], new byte[32], new byte[16], (short) 1, Set.of(), false);
        ReflectionTestUtils.setField(secret, "id", 10L);
        ReflectionTestUtils.setField(secret, "version", 3L);
        return secret;
    }

    private static Tag tag(Long id) {
        Tag tag = new Tag(TagScope.SECRET, "production", "#123456");
        ReflectionTestUtils.setField(tag, "id", id);
        return tag;
    }
}
