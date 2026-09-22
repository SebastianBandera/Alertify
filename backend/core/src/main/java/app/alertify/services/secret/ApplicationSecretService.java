package app.alertify.services.secret;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

import app.alertify.api.error.ConflictException;
import app.alertify.api.error.InvalidSecretValueException;
import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.jpa.entity.ApplicationSecret;
import app.alertify.jpa.entity.SecretValueType;
import app.alertify.jpa.entity.Tag;
import app.alertify.jpa.entity.TagScope;
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.jpa.repository.TagRepository;
import app.alertify.jpa.repository.SecretBinaryValueRepository;
import app.alertify.jpa.entity.SecretBinaryValue;
import app.alertify.binary.BinaryPayloadService;
import app.alertify.secret.api.BinarySecretCreateRequest;
import app.alertify.secret.api.BinarySecretUpdateRequest;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import app.alertify.jpa.specification.ApplicationSecretSpecifications;
import app.alertify.jpa.specification.DynamicSpecification;
import app.alertify.jpa.specification.InvalidFilterException;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.worker.contract.DatabaseCredentials;
import tools.jackson.databind.JsonNode;
import app.alertify.secret.api.SecretCreateRequest;
import app.alertify.secret.api.SecretExpressionSuggestionsResponse;
import app.alertify.secret.api.SecretExpressionValidationRequest;
import app.alertify.secret.api.SecretResponse;
import app.alertify.secret.api.SecretUpdateRequest;

/**
 * Implements the administrative lifecycle of secrets, including filtering,
 * encryption, optimistic locking, tag assignment and security event logging.
 * Secret values are accepted on writes but never included in responses.
 */
@Service
public class ApplicationSecretService {

    private static final String BINARY = "BINARY";
    private static final String CREATED_AT = "createdAt";
    private static final String SECRET = "Secret";
    private static final String SECRET_ID = "secretId";
    private static final String TAG_IDS = "tagIds";
    private static final String UPDATED_AT = "updatedAt";
    private static final String VALUE_REVISION = "valueRevision";
    private static final String VALUE_TYPE = "valueType";
    private static final String VERSION = "version";
    private static final String WRITABLE = "writable";
    private static final Map<String, String> FILTER_ALIASES = Map.of(
            "created", CREATED_AT, "modified", UPDATED_AT, "type", VALUE_TYPE
    );
    private static final Set<String> FILTER_FIELDS = Set.of(
            "id", VERSION, "name", "description", VALUE_TYPE, "encryptionVersion", VALUE_REVISION, CREATED_AT, UPDATED_AT
    );
    private static final Set<String> SORT_FIELDS = Set.of(
            "id", VERSION, "name", VALUE_TYPE, "encryptionVersion", VALUE_REVISION, CREATED_AT, UPDATED_AT
    );

    private final ApplicationSecretRepository secretRepository;
    private final TagRepository tagRepository;
    private final SecretEncryptionService encryptionService;
    private final SecretValueValidator valueValidator;
    private final SecretExpressionService expressionService;
    private final SecretMapper mapper;
    private final ApplicationEventLogger eventLogger;
    private final SecretBinaryValueRepository binaryRepository;
    private final BinaryPayloadService binaryPayloadService;

    public ApplicationSecretService(ApplicationSecretRepository secretRepository, TagRepository tagRepository, SecretEncryptionService encryptionService, SecretValueValidator valueValidator, SecretExpressionService expressionService, SecretMapper mapper, ApplicationEventLogger eventLogger, SecretBinaryValueRepository binaryRepository, BinaryPayloadService binaryPayloadService) {
        this.secretRepository = secretRepository;
        this.tagRepository = tagRepository;
        this.encryptionService = encryptionService;
        this.valueValidator = valueValidator;
        this.expressionService = expressionService;
        this.mapper = mapper;
        this.eventLogger = eventLogger;
        this.binaryRepository = binaryRepository;
        this.binaryPayloadService = binaryPayloadService;
    }

    @Transactional
    public SecretResponse createBinary(BinarySecretCreateRequest request, MultipartFile file) {
        String name = normalizeRequired(request.name()); ensureNameAvailable(name, null);
        BinaryPayloadService.PreparedBinary binary = prepare(file);
        EncryptedSecretValue placeholder = encryptionService.encrypt(BINARY);
        ApplicationSecret secret = new ApplicationSecret(name, normalizeOptional(request.description()), SecretValueType.BINARY,
                placeholder.encryptedValue(), placeholder.encryptionIv(), placeholder.valueHash(), placeholder.hashSalt(),
                placeholder.encryptionVersion(), resolveSecretTags(request.tagIds()), request.writable());
        secret.changeBinaryMetadata(binary.fileName(), binary.contentType(), binary.size(), binary.zipSize());
        ApplicationSecret saved = secretRepository.saveAndFlush(secret);
        EncryptedSecretValue encrypted = encryptionService.encryptBinary(binary.zip());
        binaryRepository.saveAndFlush(new SecretBinaryValue(saved.getId(), encrypted.encryptedValue(), encrypted.encryptionIv(),
                encrypted.valueHash(), encrypted.hashSalt(), encrypted.encryptionVersion()));
        eventLogger.successAfterCommit("SECRET_CREATED", Map.of(SECRET_ID, saved.getId(), "name", saved.getName(), VALUE_TYPE, BINARY));
        return mapper.toResponse(saved);
    }

    @Transactional
    public SecretResponse updateBinary(Long id, BinarySecretUpdateRequest request, MultipartFile file) {
        ApplicationSecret secret = find(id); verifyVersion(secret.getVersion(), request.version(), SECRET);
        BinaryPayloadService.PreparedBinary binary = prepare(file);
        String name = normalizeRequired(request.name());
        if (!secret.getName().equals(name)) { expressionService.ensureNotReferenced(secret, "renamed"); ensureNameAvailable(name, id); secret.rename(name); }
        if (secret.getValueType() != SecretValueType.BINARY) expressionService.ensureNotReferenced(secret, "changed to BINARY");
        secret.changeDescription(normalizeOptional(request.description())); secret.replaceTags(resolveSecretTags(request.tagIds()));
        secret.changeWritable(request.writable()); secret.changeValueType(SecretValueType.BINARY);
        EncryptedSecretValue placeholder = encryptionService.encrypt(BINARY);
        secret.replaceEncryptedValue(placeholder.encryptedValue(), placeholder.encryptionIv(), placeholder.valueHash(),
                placeholder.hashSalt(), placeholder.encryptionVersion());
        secret.changeBinaryMetadata(binary.fileName(), binary.contentType(), binary.size(), binary.zipSize());
        secretRepository.flush();
        EncryptedSecretValue encrypted = encryptionService.encryptBinary(binary.zip());
        binaryRepository.saveAndFlush(new SecretBinaryValue(id, encrypted.encryptedValue(), encrypted.encryptionIv(), encrypted.valueHash(), encrypted.hashSalt(), encrypted.encryptionVersion()));
        expressionService.synchronizeDependencies(secret, null);
        eventLogger.successAfterCommit("SECRET_UPDATED", Map.of(
                SECRET_ID, id, "name", secret.getName(), VALUE_TYPE, SecretValueType.BINARY,
                VALUE_REVISION, secret.getValueRevision(), WRITABLE, secret.isWritable()
        ));
        return mapper.toResponse(secret);
    }

    private BinaryPayloadService.PreparedBinary prepare(MultipartFile file) {
        try {
            if (file == null) return binaryPayloadService.prepare(new byte[0], null, null);
            return binaryPayloadService.prepare(file.getBytes(), file.getOriginalFilename(), file.getContentType());
        }
        catch (IOException | IllegalArgumentException exception) { throw new app.alertify.api.error.InvalidSecretValueException(exception.getMessage()); }
    }

    @Transactional(readOnly = true)
    public Page<SecretResponse> search(MultiValueMap<String, String> params, Pageable pageable) {
        validateSort(pageable);
        Specification<ApplicationSecret> specification = DynamicSpecification.from(params, FILTER_ALIASES, FILTER_FIELDS);
        Set<Long> tagIds = parseTagIds(params.get("tagId"));
        boolean matchAllTags = parseMatchAllTags(params.get("tagOperator"));
        if (!tagIds.isEmpty()) {
            specification = specification.and(
                    matchAllTags
                            ? ApplicationSecretSpecifications.hasAllTagIds(tagIds)
                            : ApplicationSecretSpecifications.hasAnyTagId(tagIds)
            );
        }

        Page<SecretResponse> result = secretRepository.findAll(specification, pageable).map(mapper::toResponse);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("page", result.getNumber());
        data.put("size", result.getSize());
        data.put("totalElements", result.getTotalElements());
        data.put("secretIds", result.getContent().stream().map(SecretResponse::id).toList());
        if (!tagIds.isEmpty()) {
            data.put(TAG_IDS, tagIds);
            data.put("tagOperator", matchAllTags ? "AND" : "OR");
        }
        eventLogger.successAfterCommit("SECRET_PAGE_VIEWED", data);
        return result;
    }

    @Transactional(readOnly = true)
    public SecretResponse get(Long id) {
        ApplicationSecret secret = find(id);
        eventLogger.success("SECRET_VIEWED", Map.of(SECRET_ID, secret.getId(), "name", secret.getName(), VERSION, secret.getVersion()));
        return mapper.toResponse(secret);
    }

    @Transactional
    public SecretResponse create(SecretCreateRequest request) {
        if (request.valueType() == SecretValueType.BINARY) throw new app.alertify.api.error.InvalidSecretValueException("BINARY values require multipart file upload");
        String name = normalizeRequired(request.name());
        ensureNameAvailable(name, null);
        Set<Tag> tags = resolveSecretTags(request.tagIds());
        String plaintext = valueValidator.validateAndNormalize(request.valueType(), request.value());
        EncryptedSecretValue encrypted = encryptionService.encrypt(plaintext);
        ApplicationSecret secret = new ApplicationSecret(
                name, normalizeOptional(request.description()), request.valueType(), encrypted.encryptedValue(),
                encrypted.encryptionIv(), encrypted.valueHash(), encrypted.hashSalt(), encrypted.encryptionVersion(),
                tags, request.writable()
        );
        ApplicationSecret saved = secretRepository.saveAndFlush(secret);
        expressionService.synchronizeDependencies(saved, plaintext);
        eventLogger.successAfterCommit(
                "SECRET_CREATED",
                Map.of(
                        SECRET_ID, saved.getId(), "name", saved.getName(), VALUE_TYPE, saved.getValueType(),
                        TAG_IDS, tagIds(saved.getTags()), VALUE_REVISION, saved.getValueRevision(),
                        WRITABLE, saved.isWritable()
                )
        );
        return mapper.toResponse(saved);
    }

    @Transactional
    public SecretResponse update(Long id, SecretUpdateRequest request) {
        if (request.valueType() == SecretValueType.BINARY) throw new app.alertify.api.error.InvalidSecretValueException("BINARY values require multipart file upload");
        ApplicationSecret secret = find(id);
        verifyVersion(secret.getVersion(), request.version(), SECRET);
        String previousName = secret.getName();
        String name = normalizeRequired(request.name());
        String description = normalizeOptional(request.description());
        Set<Tag> tags = resolveSecretTags(request.tagIds());
        String plaintext = valueValidator.validateAndNormalize(request.valueType(), request.newValue());
        Set<String> changedFields = new LinkedHashSet<>();

        if (!secret.getName().equals(name)) {
            ensureNameAvailable(name, id);
            expressionService.ensureNotReferenced(secret, "renamed");
            secret.rename(name);
            changedFields.add("name");
        }
        if (!Objects.equals(secret.getDescription(), description)) {
            secret.changeDescription(description);
            changedFields.add("description");
        }
        if (!tagIds(secret.getTags()).equals(tagIds(tags))) {
            secret.replaceTags(tags);
            changedFields.add("tags");
        }
        if (secret.isWritable() != request.writable()) {
            secret.changeWritable(request.writable());
            changedFields.add(WRITABLE);
        }
        if (secret.getValueType() != request.valueType()) {
            if (secret.getValueType() == SecretValueType.BINARY) { binaryRepository.deleteById(id); secret.clearBinaryMetadata(); }
            secret.changeValueType(request.valueType());
            changedFields.add(VALUE_TYPE);
        }

        EncryptedSecretValue encrypted = encryptionService.encrypt(plaintext);
        secret.replaceEncryptedValue(encrypted.encryptedValue(), encrypted.encryptionIv(), encrypted.valueHash(), encrypted.hashSalt(), encrypted.encryptionVersion());
        changedFields.add("value");
        secretRepository.flush();
        expressionService.synchronizeDependencies(secret, plaintext);

        Map<String, Object> logData = new LinkedHashMap<>();
        logData.put(SECRET_ID, id);
        logData.put("name", secret.getName());
        logData.put("previousName", previousName);
        logData.put(VALUE_TYPE, secret.getValueType());
        logData.put(TAG_IDS, tagIds(secret.getTags()));
        logData.put("changedFields", changedFields);
        logData.put(VALUE_REVISION, secret.getValueRevision());
        logData.put(WRITABLE, secret.isWritable());
        eventLogger.successAfterCommit("SECRET_UPDATED", logData);
        return mapper.toResponse(secret);
    }

    @Transactional
    public void delete(Long id, long version) {
        ApplicationSecret secret = find(id);
        verifyVersion(secret.getVersion(), version, SECRET);
        expressionService.ensureNotReferenced(secret, "deleted");
        String name = secret.getName();
        secretRepository.delete(secret);
        secretRepository.flush();
        eventLogger.successAfterCommit("SECRET_DELETED", Map.of(SECRET_ID, id, "name", name, VERSION, version));
    }

    @Transactional(readOnly = true)
    public SecretExpressionSuggestionsResponse expressionSuggestions() {
        return expressionService.suggestions();
    }

    /** Validates a draft expression without evaluating or returning its value. */
    @Transactional(readOnly = true)
    public void validateExpression(SecretExpressionValidationRequest request) {
        String expression = valueValidator.validateAndNormalizeRaw(SecretValueType.EXPRESSION, request.expression());
        expressionService.validateDraft(request.secretId(), request.name(), expression);
    }

    /** Applies the DB_SECRET validation rules to an unsaved value and returns the parsed credentials. */
    public DatabaseCredentials parseDatabaseCredentials(JsonNode value) {
        return DatabaseCredentials.fromJson(valueValidator.validateAndNormalize(SecretValueType.DB_SECRET, value));
    }

    /** Decrypts a stored DB_SECRET so its connection can be probed; the value never leaves the backend. */
    @Transactional(readOnly = true)
    public StoredDatabaseCredentials databaseCredentials(Long id) {
        ApplicationSecret secret = find(id);
        if (secret.getValueType() != SecretValueType.DB_SECRET)
            throw new InvalidSecretValueException("Secret '" + secret.getName() + "' is not a DB_SECRET");

        try {
            return new StoredDatabaseCredentials(secret.getId(), secret.getName(), DatabaseCredentials.fromJson(expressionService.resolve(secret)));
        } catch (SecretNotRecoverableException exception) {
            throw new InvalidSecretValueException("Secret '" + secret.getName() + "' cannot be decrypted with the current key");
        }
    }

    public record StoredDatabaseCredentials(long id, String name, DatabaseCredentials credentials) { }

    private ApplicationSecret find(Long id) {
        return secretRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Secret " + id + " was not found"));
    }

    private void ensureNameAvailable(String name, Long currentId) {
        boolean exists = currentId == null
                ? secretRepository.existsByNameIgnoreCase(name)
                : secretRepository.existsByNameIgnoreCaseAndIdNot(name, currentId);
        if (exists)
            throw new ConflictException("A secret named '" + name + "' already exists");
    }

    private Set<Tag> resolveSecretTags(Set<Long> requestedIds) {
        Set<Long> ids = requestedIds == null ? Set.of() : new LinkedHashSet<>(requestedIds);
        if (ids.isEmpty())
            return Set.of();

        if (ids.stream().anyMatch(id -> id == null || id <= 0))
            throw new ResourceNotFoundException("One or more secret tags were not found");

        List<Tag> found = tagRepository.findAllByIdInAndScope(ids, TagScope.SECRET);
        if (found.size() != ids.size())
            throw new ResourceNotFoundException("One or more secret tags were not found");

        return new LinkedHashSet<>(found);
    }

    private static void validateSort(Pageable pageable) {
        pageable.getSort().forEach(order -> {
            if (!SORT_FIELDS.contains(order.getProperty()))
                throw new InvalidFilterException("sort=" + order.getProperty());
        });
    }

    private static Set<Long> parseTagIds(List<String> rawValues) {
        if (rawValues == null || rawValues.isEmpty())
            return Set.of();

        try {
            return rawValues.stream().map(String::trim).map(Long::valueOf).peek(value -> {
                if (value <= 0)
                    throw new NumberFormatException("Tag ID must be positive");
            }).collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (RuntimeException exception) {
            throw new InvalidFilterException("tagId", exception);
        }
    }

    private static boolean parseMatchAllTags(List<String> rawValues) {
        if (rawValues == null || rawValues.isEmpty())
            return false;

        if (rawValues.size() != 1)
            throw new InvalidFilterException("tagOperator");

        return switch (rawValues.get(0).trim().toUpperCase(Locale.ROOT)) {
            case "OR" -> false;
            case "AND" -> true;
            default -> throw new InvalidFilterException("tagOperator");
        };
    }

    private static Set<Long> tagIds(Set<Tag> tags) {
        return tags.stream().map(Tag::getId).collect(Collectors.toSet());
    }

    private static String normalizeRequired(String value) {
        return value.trim();
    }

    private static String normalizeOptional(String value) {
        if (value == null)
            return null;

        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    static void verifyVersion(long currentVersion, long requestedVersion, String resourceName) {
        if (currentVersion != requestedVersion)
            throw new ConflictException(resourceName + " was modified by another request; reload it and try again");
    }
}
