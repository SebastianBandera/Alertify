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
import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.jpa.entity.ApplicationSecret;
import app.alertify.jpa.entity.Tag;
import app.alertify.jpa.entity.TagScope;
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.jpa.repository.TagRepository;
import app.alertify.jpa.specification.ApplicationSecretSpecifications;
import app.alertify.jpa.specification.DynamicSpecification;
import app.alertify.jpa.specification.InvalidFilterException;
import app.alertify.logging.ApplicationEventLogger;
import app.alertify.secret.api.SecretCreateRequest;
import app.alertify.secret.api.SecretResponse;
import app.alertify.secret.api.SecretUpdateRequest;

/**
 * Implements the administrative lifecycle of secrets, including filtering,
 * encryption, optimistic locking, tag assignment and security event logging.
 * Secret values are accepted on writes but never included in responses.
 */
@Service
public class ApplicationSecretService {

    private static final Map<String, String> FILTER_ALIASES = Map.of(
            "created", "createdAt", "modified", "updatedAt"
    );
    private static final Set<String> FILTER_FIELDS = Set.of(
            "id", "version", "name", "description", "encryptionVersion", "valueRevision", "createdAt", "updatedAt"
    );
    private static final Set<String> SORT_FIELDS = Set.of(
            "id", "version", "name", "encryptionVersion", "valueRevision", "createdAt", "updatedAt"
    );

    private final ApplicationSecretRepository secretRepository;
    private final TagRepository tagRepository;
    private final SecretEncryptionService encryptionService;
    private final SecretMapper mapper;
    private final ApplicationEventLogger eventLogger;

    public ApplicationSecretService(ApplicationSecretRepository secretRepository, TagRepository tagRepository, SecretEncryptionService encryptionService, SecretMapper mapper, ApplicationEventLogger eventLogger) {
        this.secretRepository = secretRepository;
        this.tagRepository = tagRepository;
        this.encryptionService = encryptionService;
        this.mapper = mapper;
        this.eventLogger = eventLogger;
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
            data.put("tagIds", tagIds);
            data.put("tagOperator", matchAllTags ? "AND" : "OR");
        }
        eventLogger.successAfterCommit("SECRET_PAGE_VIEWED", data);
        return result;
    }

    @Transactional(readOnly = true)
    public SecretResponse get(Long id) {
        ApplicationSecret secret = find(id);
        eventLogger.success("SECRET_VIEWED", Map.of("secretId", secret.getId(), "name", secret.getName(), "version", secret.getVersion()));
        return mapper.toResponse(secret);
    }

    @Transactional
    public SecretResponse create(SecretCreateRequest request) {
        String name = normalizeRequired(request.name());
        ensureNameAvailable(name, null);
        Set<Tag> tags = resolveSecretTags(request.tagIds());
        EncryptedSecretValue encrypted = encryptionService.encrypt(request.value());
        ApplicationSecret secret = new ApplicationSecret(
                name, normalizeOptional(request.description()), encrypted.encryptedValue(), encrypted.encryptionIv(),
                encrypted.valueHash(), encrypted.hashSalt(), encrypted.encryptionVersion(), tags
        );
        ApplicationSecret saved = secretRepository.saveAndFlush(secret);
        eventLogger.successAfterCommit(
                "SECRET_CREATED",
                Map.of("secretId", saved.getId(), "name", saved.getName(), "tagIds", tagIds(saved.getTags()), "valueRevision", saved.getValueRevision())
        );
        return mapper.toResponse(saved);
    }

    @Transactional
    public SecretResponse update(Long id, SecretUpdateRequest request) {
        ApplicationSecret secret = find(id);
        verifyVersion(secret.getVersion(), request.version(), "Secret");
        String previousName = secret.getName();
        String name = normalizeRequired(request.name());
        String description = normalizeOptional(request.description());
        Set<Tag> tags = resolveSecretTags(request.tagIds());
        Set<String> changedFields = new LinkedHashSet<>();

        if (!secret.getName().equals(name)) {
            ensureNameAvailable(name, id);
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

        EncryptedSecretValue encrypted = encryptionService.encrypt(request.newValue());
        secret.replaceEncryptedValue(encrypted.encryptedValue(), encrypted.encryptionIv(), encrypted.valueHash(), encrypted.hashSalt(), encrypted.encryptionVersion());
        changedFields.add("value");
        secretRepository.flush();

        Map<String, Object> logData = new LinkedHashMap<>();
        logData.put("secretId", id);
        logData.put("name", secret.getName());
        logData.put("previousName", previousName);
        logData.put("tagIds", tagIds(secret.getTags()));
        logData.put("changedFields", changedFields);
        logData.put("valueRevision", secret.getValueRevision());
        eventLogger.successAfterCommit("SECRET_UPDATED", logData);
        return mapper.toResponse(secret);
    }

    @Transactional
    public void delete(Long id, long version) {
        ApplicationSecret secret = find(id);
        verifyVersion(secret.getVersion(), version, "Secret");
        String name = secret.getName();
        secretRepository.delete(secret);
        secretRepository.flush();
        eventLogger.successAfterCommit("SECRET_DELETED", Map.of("secretId", id, "name", name, "version", version));
    }

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
