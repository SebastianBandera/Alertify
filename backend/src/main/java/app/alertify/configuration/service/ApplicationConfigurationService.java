package app.alertify.configuration.service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import tools.jackson.databind.JsonNode;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

import app.alertify.api.error.ConflictException;
import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.configuration.api.ConfigurationCreateRequest;
import app.alertify.configuration.api.ConfigurationResponse;
import app.alertify.configuration.api.ConfigurationUpdateRequest;
import app.alertify.jpa.entity.ApplicationConfiguration;
import app.alertify.jpa.entity.ConfigurationValueType;
import app.alertify.jpa.entity.Tag;
import app.alertify.jpa.entity.TagScope;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;
import app.alertify.jpa.repository.TagRepository;
import app.alertify.jpa.specification.ApplicationConfigurationSpecifications;
import app.alertify.jpa.specification.DynamicSpecification;
import app.alertify.jpa.specification.InvalidFilterException;
import app.alertify.logging.ApplicationEventLogger;

@Service
public class ApplicationConfigurationService {

    private static final Map<String, String> FILTER_ALIASES = Map.of(
        "type", "valueType", "created", "createdAt", "modified", "updatedAt"
    );
    private static final Set<String> FILTER_FIELDS = Set.of(
        "id", "version", "name", "description", "valueType", "createdAt", "updatedAt"
    );
    private static final Set<String> SORT_FIELDS = Set.of(
        "id", "version", "name", "valueType", "createdAt", "updatedAt"
    );

    private final ApplicationConfigurationRepository configurationRepository;
    private final TagRepository tagRepository;
    private final ConfigurationValueValidator valueValidator;
    private final ApplicationConfigurationLookupService lookupService;
    private final ConfigurationCacheInvalidator cacheInvalidator;
    private final ApplicationEventLogger eventLogger;

    public ApplicationConfigurationService(
            ApplicationConfigurationRepository configurationRepository,
            TagRepository tagRepository,
            ConfigurationValueValidator valueValidator,
            ApplicationConfigurationLookupService lookupService,
            ConfigurationCacheInvalidator cacheInvalidator,
            ApplicationEventLogger eventLogger) {
        this.configurationRepository = configurationRepository;
        this.tagRepository = tagRepository;
        this.valueValidator = valueValidator;
        this.lookupService = lookupService;
        this.cacheInvalidator = cacheInvalidator;
        this.eventLogger = eventLogger;
    }

    @Transactional(readOnly = true)
    public Page<ConfigurationResponse> search(
            MultiValueMap<String, String> params, Pageable pageable) {
        SearchValidation.validateSort(pageable, SORT_FIELDS);
        Specification<ApplicationConfiguration> specification =
            DynamicSpecification.from(params, FILTER_ALIASES, FILTER_FIELDS);

        Set<Long> tagIds = parseTagIds(params.get("tagId"));
        boolean matchAllTags = parseMatchAllTags(params.get("tagOperator"));
        if (!tagIds.isEmpty()) {
            specification = specification.and(matchAllTags
                ? ApplicationConfigurationSpecifications.hasAllTagIds(tagIds)
                : ApplicationConfigurationSpecifications.hasAnyTagId(tagIds));
        }

        Page<ConfigurationResponse> result = configurationRepository.findAll(specification, pageable)
            .map(ConfigurationMapper::toResponse);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("page", result.getNumber());
        data.put("size", result.getSize());
        data.put("totalElements", result.getTotalElements());
        data.put("configurationIds", result.getContent().stream().map(ConfigurationResponse::id).toList());
        if (!tagIds.isEmpty()) {
            data.put("tagIds", tagIds);
            data.put("tagOperator", matchAllTags ? "AND" : "OR");
        }
        String nameFilter = params.getFirst("name");
        if (nameFilter != null && !nameFilter.isBlank()) data.put("nameFilter", nameFilter);
        eventLogger.successAfterCommit("CONFIGURATION_PAGE_VIEWED", data);
        return result;
    }

    public ConfigurationResponse get(Long id) {
        ConfigurationResponse response = lookupService.getById(id);
        eventLogger.success(
            "CONFIGURATION_VIEWED",
            Map.of("configurationId", response.id(), "name", response.name(), "version", response.version())
        );
        return response;
    }

    @Transactional
    public ConfigurationResponse create(ConfigurationCreateRequest request) {
        String name = normalizeRequired(request.name());
        SystemConfigurationPolicy.validateCreation(name);
        ensureNameAvailable(name, null);

        JsonNode value = valueValidator.validateAndNormalize(request.valueType(), request.value());
        Set<Tag> tags = resolveConfigurationTags(request.tagIds());
        ApplicationConfiguration configuration = new ApplicationConfiguration(
            name, normalizeOptional(request.description()), request.valueType(), value, tags
        );
        ApplicationConfiguration saved = configurationRepository.saveAndFlush(configuration);
        cacheInvalidator.evictAfterCommit(saved.getId(), Set.of(saved.getName()));
        eventLogger.successAfterCommit(
            "CONFIGURATION_CREATED",
            Map.of("configurationId", saved.getId(), "name", saved.getName(),
                "valueType", saved.getValueType().name(), "tagIds", tagIds(saved.getTags()))
        );
        return ConfigurationMapper.toResponse(saved);
    }

    @Transactional
    public ConfigurationResponse update(Long id, ConfigurationUpdateRequest request) {
        ApplicationConfiguration configuration = find(id);
        verifyVersion(configuration.getVersion(), request.version(), "Configuration");
        String previousName = configuration.getName();

        String name = normalizeRequired(request.name());
        String description = normalizeOptional(request.description());
        JsonNode value = valueValidator.validateAndNormalize(request.valueType(), request.value());
        SystemConfigurationPolicy.validateUpdate(
            configuration, name, request.valueType(), value
        );
        Set<Tag> tags = resolveConfigurationTags(request.tagIds());
        Set<String> changedFields = new LinkedHashSet<>();

        if (!configuration.getName().equals(name)) {
            ensureNameAvailable(name, id);
            configuration.rename(name);
            changedFields.add("name");
        }
        if (!Objects.equals(configuration.getDescription(), description)) {
            configuration.changeDescription(description);
            changedFields.add("description");
        }
        boolean valueTypeChanged = configuration.getValueType() != request.valueType();
        if (valueTypeChanged
                || !valuesEqual(request.valueType(), configuration.getValue(), value)) {
            configuration.changeValue(request.valueType(), value);
            changedFields.add("value");
            if (valueTypeChanged) changedFields.add("valueType");
        }
        if (!tagIds(configuration.getTags()).equals(tagIds(tags))) {
            configuration.replaceTags(tags);
            changedFields.add("tags");
        }

        if (!changedFields.isEmpty()) {
            configurationRepository.flush();
            cacheInvalidator.evictAfterCommit(
                id,
                new LinkedHashSet<>(List.of(previousName, configuration.getName()))
            );
        }
        Map<String, Object> logData = new LinkedHashMap<>();
        logData.put("configurationId", id);
        logData.put("name", configuration.getName());
        logData.put("previousName", previousName);
        logData.put("valueType", configuration.getValueType().name());
        logData.put("tagIds", tagIds(configuration.getTags()));
        logData.put("changed", !changedFields.isEmpty());
        logData.put("changedFields", changedFields);
        eventLogger.successAfterCommit("CONFIGURATION_UPDATED", logData);
        return ConfigurationMapper.toResponse(configuration);
    }

    @Transactional
    public void delete(Long id, long version) {
        ApplicationConfiguration configuration = find(id);
        verifyVersion(configuration.getVersion(), version, "Configuration");
        SystemConfigurationPolicy.validateDeletion(configuration);
        String name = configuration.getName();
        configurationRepository.delete(configuration);
        configurationRepository.flush();
        cacheInvalidator.evictAfterCommit(id, Set.of(name));
        eventLogger.successAfterCommit(
            "CONFIGURATION_DELETED",
            Map.of("configurationId", id, "name", name, "version", version)
        );
    }

    private ApplicationConfiguration find(Long id) {
        return configurationRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Configuration " + id + " was not found"));
    }

    private void ensureNameAvailable(String name, Long currentId) {
        boolean exists = currentId == null
            ? configurationRepository.existsByNameIgnoreCase(name)
            : configurationRepository.existsByNameIgnoreCaseAndIdNot(name, currentId);
        if (exists) throw new ConflictException("A configuration named '" + name + "' already exists");
    }

    private Set<Tag> resolveConfigurationTags(Set<Long> requestedIds) {
        Set<Long> ids = requestedIds == null ? Set.of() : new LinkedHashSet<>(requestedIds);
        if (ids.isEmpty()) return Set.of();
        if (ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new ResourceNotFoundException("One or more configuration tags were not found");
        }

        List<Tag> found = tagRepository.findAllByIdInAndScope(ids, TagScope.CONFIGURATION);
        if (found.size() != ids.size()) {
            throw new ResourceNotFoundException("One or more configuration tags were not found");
        }
        return new LinkedHashSet<>(found);
    }

    private static Set<Long> parseTagIds(List<String> rawValues) {
        if (rawValues == null || rawValues.isEmpty()) return Set.of();
        try {
            return rawValues.stream()
                .map(String::trim).map(Long::valueOf)
                .peek(value -> {
                    if (value <= 0) throw new NumberFormatException("Tag ID must be positive");
                })
                .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (RuntimeException exception) {
            throw new InvalidFilterException("tagId", exception);
        }
    }

    private static boolean parseMatchAllTags(List<String> rawValues) {
        if (rawValues == null || rawValues.isEmpty()) return false;
        if (rawValues.size() != 1) throw new InvalidFilterException("tagOperator");

        return switch (rawValues.get(0).trim().toUpperCase(Locale.ROOT)) {
            case "OR" -> false;
            case "AND" -> true;
            default -> throw new InvalidFilterException("tagOperator");
        };
    }

    private static Set<Long> tagIds(Set<Tag> tags) {
        return tags.stream().map(Tag::getId).collect(Collectors.toSet());
    }

    private static boolean valuesEqual(
            ConfigurationValueType type,
            JsonNode currentValue,
            JsonNode requestedValue) {
        return switch (type) {
            case INTEGER -> currentValue.isIntegralNumber()
                && requestedValue.isIntegralNumber()
                && currentValue.bigIntegerValue().equals(requestedValue.bigIntegerValue());
            case DECIMAL -> currentValue.isNumber()
                && requestedValue.isNumber()
                && currentValue.decimalValue().compareTo(requestedValue.decimalValue()) == 0;
            default -> currentValue.equals(requestedValue);
        };
    }

    private static String normalizeRequired(String value) { return value.trim(); }

    private static String normalizeOptional(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    static void verifyVersion(long currentVersion, long requestedVersion, String resourceName) {
        if (currentVersion != requestedVersion) {
            throw new ConflictException(
                resourceName + " was modified by another request; reload it and try again"
            );
        }
    }
}
