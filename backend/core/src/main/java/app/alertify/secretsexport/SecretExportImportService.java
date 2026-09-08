package app.alertify.secretsexport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.FileHeader;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.EncryptionMethod;

import app.alertify.jpa.entity.ApplicationSecret;
import app.alertify.jpa.entity.Tag;
import app.alertify.jpa.entity.TagScope;
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.jpa.repository.TagRepository;
import app.alertify.services.secret.EncryptedSecretValue;
import app.alertify.services.secret.SecretEncryptionService;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads and writes the AES-256 encrypted ZIP archive used by
 * {@link SecretExportImportCli}. Existing secrets are never overwritten on
 * import; a name that already exists is reported back as skipped instead.
 */
@Service
@Profile(SecretExportImportConfiguration.PROFILE)
class SecretExportImportService {

    private static final String ENTRY_NAME = "secrets.json";
    private static final DateTimeFormatter FILE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final ApplicationSecretRepository secretRepository;
    private final TagRepository tagRepository;
    private final SecretEncryptionService encryptionService;
    private final JsonMapper jsonMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    SecretExportImportService(ApplicationSecretRepository secretRepository, TagRepository tagRepository,
            SecretEncryptionService encryptionService, JsonMapper jsonMapper) {
        this.secretRepository = secretRepository;
        this.tagRepository = tagRepository;
        this.encryptionService = encryptionService;
        this.jsonMapper = jsonMapper;
    }

    @Transactional(readOnly = true)
    ExportResult export(Path directory) {
        List<SecretExportPayload.Entry> entries = new ArrayList<>();
        for (ApplicationSecret secret : secretRepository.findAll()) {
            String value = encryptionService.decrypt(secret);
            List<SecretExportPayload.TagExport> tags = secret.getTags().stream()
                    .sorted(Comparator.comparing(Tag::getName, String.CASE_INSENSITIVE_ORDER))
                    .map(tag -> new SecretExportPayload.TagExport(tag.getName(), tag.getColor()))
                    .toList();
            entries.add(new SecretExportPayload.Entry(secret.getName(), secret.getDescription(), value, secret.isWritable(), tags));
        }

        SecretExportPayload payload = new SecretExportPayload(Instant.now(), List.copyOf(entries));
        char[] password = generatePassword();
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve("alertify-secrets-" + FILE_TIMESTAMP.format(payload.exportedAt()) + ".zip");
            writeEncryptedZip(file, payload, password);
            return new ExportResult(file, new String(password), entries.size());
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to write the secrets export archive", exception);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    @Transactional
    ImportResult importFrom(Path file, char[] password) {
        SecretExportPayload payload = readEncryptedZip(file, password);

        Map<String, Tag> tagsByName = new LinkedHashMap<>();
        for (Tag tag : tagRepository.findAllByScope(TagScope.SECRET))
            tagsByName.put(tag.getName().toLowerCase(Locale.ROOT), tag);

        List<String> created = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (SecretExportPayload.Entry entry : payload.secrets()) {
            if (secretRepository.findByNameIgnoreCase(entry.name()).isPresent()) {
                skipped.add(entry.name());
                continue;
            }

            Set<Tag> resolvedTags = new LinkedHashSet<>();
            for (SecretExportPayload.TagExport tagExport : entry.tags()) {
                Tag tag = tagsByName.computeIfAbsent(tagExport.name().toLowerCase(Locale.ROOT),
                        _ -> tagRepository.save(new Tag(TagScope.SECRET, tagExport.name(), tagExport.color())));
                resolvedTags.add(tag);
            }

            EncryptedSecretValue encrypted = encryptionService.encrypt(entry.value());
            secretRepository.save(new ApplicationSecret(entry.name(), entry.description(), encrypted.encryptedValue(),
                    encrypted.encryptionIv(), encrypted.valueHash(), encrypted.hashSalt(), encrypted.encryptionVersion(),
                    resolvedTags, entry.writable()));
            created.add(entry.name());
        }
        return new ImportResult(List.copyOf(created), List.copyOf(skipped));
    }

    private void writeEncryptedZip(Path file, SecretExportPayload payload, char[] password) throws IOException {
        byte[] json = jsonMapper.writeValueAsBytes(payload);
        ZipParameters parameters = new ZipParameters();
        parameters.setEncryptFiles(true);
        parameters.setEncryptionMethod(EncryptionMethod.AES);
        parameters.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);
        parameters.setFileNameInZip(ENTRY_NAME);
        try (ZipFile zipFile = new ZipFile(file.toFile(), password);
                InputStream input = new ByteArrayInputStream(json)) {
            zipFile.addStream(input, parameters);
        } finally {
            Arrays.fill(json, (byte) 0);
        }
    }

    private SecretExportPayload readEncryptedZip(Path file, char[] password) {
        try (ZipFile zipFile = new ZipFile(file.toFile(), password)) {
            if (!zipFile.isValidZipFile())
                throw new IllegalStateException("The file is not a valid ZIP archive");

            FileHeader header = zipFile.getFileHeader(ENTRY_NAME);
            if (header == null)
                throw new IllegalStateException("The archive does not contain " + ENTRY_NAME);

            try (InputStream input = zipFile.getInputStream(header)) {
                return jsonMapper.readValue(input.readAllBytes(), SecretExportPayload.class);
            }
        } catch (ZipException exception) {
            throw new IllegalStateException("Incorrect password or corrupted archive", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read the secrets archive", exception);
        }
    }

    private char[] generatePassword() {
        byte[] random = new byte[32];
        secureRandom.nextBytes(random);
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(random).toCharArray();
        } finally {
            Arrays.fill(random, (byte) 0);
        }
    }

    record ExportResult(Path file, String password, int count) {
    }

    record ImportResult(List<String> created, List<String> skipped) {
    }
}
