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
import net.lingala.zip4j.model.enums.CompressionMethod;

import app.alertify.binary.BinaryPayloadService;
import app.alertify.jpa.entity.ApplicationSecret;
import app.alertify.jpa.entity.SecretBinaryValue;
import app.alertify.jpa.entity.SecretValueType;
import app.alertify.jpa.entity.SystemConfiguration;
import app.alertify.jpa.entity.Tag;
import app.alertify.jpa.entity.TagScope;
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.jpa.repository.SecretBinaryValueRepository;
import app.alertify.jpa.repository.SystemConfigurationRepository;
import app.alertify.jpa.repository.TagRepository;
import app.alertify.services.secret.EncryptedSecretValue;
import app.alertify.services.secret.SecretEncryptionService;
import app.alertify.services.secret.SecretExpressionDependencySynchronizer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads and writes the AES-256 encrypted ZIP archive used by
 * {@link SecretExportImportCli}. The archive holds two independent JSON
 * entries, {@code secrets.json} and {@code system-configurations.json}.
 * Existing rows (matched by name) are never overwritten on import in either
 * case; a name that already exists is reported back as skipped instead.
 * Including system configurations (e.g. {@code KEY_PART}) is deliberately
 * opt-in-by-presence rather than required: an archive written by an older
 * version of this tool without a system-configurations entry still imports
 * cleanly, just with zero system configurations.
 */
@Service
@Profile(SecretExportImportConfiguration.PROFILE)
class SecretExportImportService {

    private static final String SECRETS_ENTRY_NAME = "secrets.json";
    private static final String SYSTEM_CONFIGURATIONS_ENTRY_NAME = "system-configurations.json";
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final ApplicationSecretRepository secretRepository;
    private final SystemConfigurationRepository systemConfigurationRepository;
    private final TagRepository tagRepository;
    private final SecretEncryptionService encryptionService;
    private final SecretExpressionDependencySynchronizer expressionDependencySynchronizer;
    private final SecretBinaryValueRepository binaryRepository;
    private final BinaryPayloadService binaryPayloadService;
    private final JsonMapper jsonMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    SecretExportImportService(ApplicationSecretRepository secretRepository, SystemConfigurationRepository systemConfigurationRepository,
            TagRepository tagRepository, SecretEncryptionService encryptionService,
            SecretExpressionDependencySynchronizer expressionDependencySynchronizer,
            JsonMapper jsonMapper, SecretBinaryValueRepository binaryRepository, BinaryPayloadService binaryPayloadService) {
        this.secretRepository = secretRepository;
        this.systemConfigurationRepository = systemConfigurationRepository;
        this.tagRepository = tagRepository;
        this.encryptionService = encryptionService;
        this.expressionDependencySynchronizer = expressionDependencySynchronizer;
        this.jsonMapper = jsonMapper;
        this.binaryRepository = binaryRepository;
        this.binaryPayloadService = binaryPayloadService;
    }

    @Transactional(readOnly = true)
    ExportResult export(Path directory) {
        List<SecretExportPayload.Entry> secretEntries = new ArrayList<>();
        Map<String, Long> binaryEntries = new LinkedHashMap<>();
        for (ApplicationSecret secret : secretRepository.findAll()) {
            String value = secret.getValueType() == SecretValueType.BINARY ? null : encryptionService.decrypt(secret);
            List<SecretExportPayload.TagExport> tags = secret.getTags().stream()
                    .sorted(Comparator.comparing(Tag::getName, String.CASE_INSENSITIVE_ORDER))
                    .map(tag -> new SecretExportPayload.TagExport(tag.getName(), tag.getColor()))
                    .toList();
            String binaryEntry = null;
            if (secret.getValueType() == SecretValueType.BINARY) {
                binaryEntry = "binary-secrets/" + secret.getId() + ".zip";
                binaryEntries.put(binaryEntry, secret.getId());
            }
            secretEntries.add(new SecretExportPayload.Entry(secret.getName(), secret.getDescription(), secret.getValueType(), value,
                    secret.isWritable(), tags, binaryEntry, secret.getBinaryFileName(), secret.getBinaryContentType(),
                    secret.getBinarySize(), secret.getBinaryZipSize()));
        }

        List<SystemConfigurationExportPayload.Entry> systemConfigurationEntries = new ArrayList<>();
        for (SystemConfiguration configuration : systemConfigurationRepository.findAll()) {
            systemConfigurationEntries.add(new SystemConfigurationExportPayload.Entry(
                    configuration.getName(), configuration.getValue(), configuration.isValueHidden()));
        }

        Instant exportedAt = Instant.now();
        SecretExportPayload secretsPayload = new SecretExportPayload(exportedAt, List.copyOf(secretEntries));
        SystemConfigurationExportPayload systemConfigurationsPayload =
                new SystemConfigurationExportPayload(exportedAt, List.copyOf(systemConfigurationEntries));

        char[] password = generatePassword();
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve("alertify-secrets-" + FILE_TIMESTAMP.format(exportedAt) + ".zip");
            try (ZipFile zipFile = new ZipFile(file.toFile(), password)) {
                writeEntry(zipFile, SECRETS_ENTRY_NAME, secretsPayload);
                writeEntry(zipFile, SYSTEM_CONFIGURATIONS_ENTRY_NAME, systemConfigurationsPayload);
                for (Map.Entry<String, Long> binaryEntry : binaryEntries.entrySet()) {
                    SecretBinaryValue value = binaryRepository.findById(binaryEntry.getValue())
                            .orElseThrow(() -> new IllegalStateException("Binary secret payload is missing: " + binaryEntry.getValue()));
                    byte[] decrypted = encryptionService.decryptBinary(value);
                    try {
                        writeStoredEntry(zipFile, binaryEntry.getKey(), decrypted);
                    } finally {
                        Arrays.fill(decrypted, (byte) 0);
                    }
                }
            }
            return new ExportResult(file, new String(password), secretEntries.size(), systemConfigurationEntries.size());
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to write the secrets export archive", exception);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    @Transactional
    ImportResult importFrom(Path file, char[] password) {
        try (ZipFile zipFile = openForRead(file, password)) {
            SecretExportPayload secretsPayload = readEntry(zipFile, SECRETS_ENTRY_NAME, SecretExportPayload.class);
            if (secretsPayload == null)
                throw new IllegalStateException("The archive does not contain " + SECRETS_ENTRY_NAME);

            SystemConfigurationExportPayload systemConfigurationsPayload =
                    readEntry(zipFile, SYSTEM_CONFIGURATIONS_ENTRY_NAME, SystemConfigurationExportPayload.class);
            List<SystemConfigurationExportPayload.Entry> systemConfigurationEntries = systemConfigurationsPayload == null
                    ? List.of() : systemConfigurationsPayload.systemConfigurations();

            SecretImportResult secretResult = importSecrets(zipFile, secretsPayload);
            SystemConfigurationImportResult systemConfigurationResult = importSystemConfigurations(systemConfigurationEntries);
            return new ImportResult(secretResult, systemConfigurationResult);
        } catch (ZipException exception) {
            throw new IllegalStateException("Incorrect password or corrupted archive", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read the secrets archive", exception);
        }
    }

    private SecretImportResult importSecrets(ZipFile zipFile, SecretExportPayload payload) throws IOException {
        Map<String, Tag> tagsByName = new LinkedHashMap<>();
        for (Tag tag : tagRepository.findAllByScope(TagScope.SECRET))
            tagsByName.put(tag.getName().toLowerCase(Locale.ROOT), tag);

        List<String> created = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Map<ApplicationSecret, String> importedPlaintexts = new LinkedHashMap<>();
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

            boolean binary = entry.valueTypeOrDefault() == SecretValueType.BINARY;
            byte[] storedZip = binary ? readBinaryEntry(zipFile, entry.binaryEntry()) : null;
            BinaryPayloadService.PreparedBinary prepared = null;
            if (binary) {
                byte[] raw = binaryPayloadService.decompress(storedZip);
                try {
                    prepared = binaryPayloadService.prepare(raw, entry.binaryFileName(), entry.binaryContentType());
                } finally {
                    Arrays.fill(raw, (byte) 0);
                    Arrays.fill(storedZip, (byte) 0);
                }
            }
            EncryptedSecretValue encrypted = binary ? encryptionService.encrypt("BINARY") : encryptionService.encrypt(entry.value());
            ApplicationSecret saved = secretRepository.save(new ApplicationSecret(entry.name(), entry.description(), entry.valueTypeOrDefault(),
                    encrypted.encryptedValue(), encrypted.encryptionIv(), encrypted.valueHash(), encrypted.hashSalt(),
                    encrypted.encryptionVersion(), resolvedTags, entry.writable()));
            if (binary) {
                saved.changeBinaryMetadata(prepared.fileName(), prepared.contentType(), prepared.size(), prepared.zipSize());
                byte[] preparedZip = prepared.zip();
                try {
                    EncryptedSecretValue encryptedBinary = encryptionService.encryptBinary(preparedZip);
                    binaryRepository.save(new SecretBinaryValue(saved.getId(), encryptedBinary.encryptedValue(), encryptedBinary.encryptionIv(),
                            encryptedBinary.valueHash(), encryptedBinary.hashSalt(), encryptedBinary.encryptionVersion()));
                } finally {
                    Arrays.fill(preparedZip, (byte) 0);
                }
            } else {
                importedPlaintexts.put(saved, entry.value());
            }
            created.add(entry.name());
        }
        // Expression dependencies are resolved after every secret exists, so cross references inside the archive work.
        secretRepository.flush();
        importedPlaintexts.forEach(expressionDependencySynchronizer::synchronize);
        return new SecretImportResult(List.copyOf(created), List.copyOf(skipped));
    }

    private SystemConfigurationImportResult importSystemConfigurations(List<SystemConfigurationExportPayload.Entry> entries) {
        List<String> created = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (SystemConfigurationExportPayload.Entry entry : entries) {
            if (systemConfigurationRepository.findByNameIgnoreCase(entry.name()).isPresent()) {
                skipped.add(entry.name());
                continue;
            }

            systemConfigurationRepository.save(new SystemConfiguration(entry.name(), entry.value(), entry.valueHidden()));
            created.add(entry.name());
        }
        return new SystemConfigurationImportResult(List.copyOf(created), List.copyOf(skipped));
    }

    private void writeEntry(ZipFile zipFile, String entryName, Object payload) throws IOException {
        byte[] json = jsonMapper.writeValueAsBytes(payload);
        ZipParameters parameters = new ZipParameters();
        parameters.setEncryptFiles(true);
        parameters.setEncryptionMethod(EncryptionMethod.AES);
        parameters.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);
        parameters.setFileNameInZip(entryName);
        try (InputStream input = new ByteArrayInputStream(json)) {
            zipFile.addStream(input, parameters);
        } finally {
            Arrays.fill(json, (byte) 0);
        }
    }

    private void writeStoredEntry(ZipFile zipFile, String entryName, byte[] value) throws IOException {
        ZipParameters parameters = encryptedParameters(entryName);
        parameters.setCompressionMethod(CompressionMethod.STORE);
        try (InputStream input = new ByteArrayInputStream(value)) {
            zipFile.addStream(input, parameters);
        }
    }

    private ZipParameters encryptedParameters(String entryName) {
        ZipParameters parameters = new ZipParameters();
        parameters.setEncryptFiles(true);
        parameters.setEncryptionMethod(EncryptionMethod.AES);
        parameters.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);
        parameters.setFileNameInZip(entryName);
        return parameters;
    }

    private byte[] readBinaryEntry(ZipFile zipFile, String entryName) throws IOException {
        if (entryName == null || entryName.isBlank())
            throw new IllegalStateException("Binary secret metadata does not identify its archive entry");
        FileHeader header = zipFile.getFileHeader(entryName);
        if (header == null)
            throw new IllegalStateException("The archive does not contain " + entryName);
        long maximumArchiveBytes = (long) binaryPayloadService.maximumBytes() + 1024 * 1024;
        if (header.getUncompressedSize() > maximumArchiveBytes)
            throw new IllegalStateException("Binary secret archive entry exceeds the allowed size");
        try (InputStream input = zipFile.getInputStream(header)) {
            byte[] value = input.readNBytes((int) maximumArchiveBytes + 1);
            if (value.length > maximumArchiveBytes)
                throw new IllegalStateException("Binary secret archive entry exceeds the allowed size");
            return value;
        }
    }

    private ZipFile openForRead(Path file, char[] password) {
        ZipFile zipFile = new ZipFile(file.toFile(), password);
        if (!zipFile.isValidZipFile())
            throw new IllegalStateException("The file is not a valid ZIP archive");

        return zipFile;
    }

    private <T> T readEntry(ZipFile zipFile, String entryName, Class<T> type) throws IOException {
        FileHeader header = zipFile.getFileHeader(entryName);
        if (header == null)
            return null;

        try (InputStream input = zipFile.getInputStream(header)) {
            return jsonMapper.readValue(input.readAllBytes(), type);
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

    record ExportResult(Path file, String password, int secretCount, int systemConfigurationCount) {
    }

    record SecretImportResult(List<String> created, List<String> skipped) {
    }

    record SystemConfigurationImportResult(List<String> created, List<String> skipped) {
    }

    record ImportResult(SecretImportResult secrets, SystemConfigurationImportResult systemConfigurations) {
    }
}
