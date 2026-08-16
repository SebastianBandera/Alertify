package app.alertify.configuration.service;

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

    public ApplicationConfigurationService(
            ApplicationConfigurationRepository configurationRepository,
            TagRepository tagRepository,
            ConfigurationValueValidator valueValidator) {
        this.configurationRepository = configurationRepository;
        this.tagRepository = tagRepository;
        this.valueValidator = valueValidator;
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

        return configurationRepository.findAll(specification, pageable)
            .map(ConfigurationMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public ConfigurationResponse get(Long id) {
        return ConfigurationMapper.toResponse(find(id));
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
        return ConfigurationMapper.toResponse(configurationRepository.saveAndFlush(configuration));
    }

    @Transactional
    public ConfigurationResponse update(Long id, ConfigurationUpdateRequest request) {
        ApplicationConfiguration configuration = find(id);
        verifyVersion(configuration.getVersion(), request.version(), "Configuration");

        String name = normalizeRequired(request.name());
        String description = normalizeOptional(request.description());
        JsonNode value = valueValidator.validateAndNormalize(request.valueType(), request.value());
        SystemConfigurationPolicy.validateUpdate(
            configuration, name, request.valueType(), value
        );
        if (SystemConfigurationPolicy.isSystemManaged(configuration.getName())) {
            value = tools.jackson.databind.node.StringNode.valueOf(
                SystemConfigurationPolicy.normalizeKeyPart(value.stringValue())
            );
        }
        Set<Tag> tags = resolveConfigurationTags(request.tagIds());
        boolean changed = false;

        if (!configuration.getName().equals(name)) {
            ensureNameAvailable(name, id);
            configuration.rename(name);
            changed = true;
        }
        if (!Objects.equals(configuration.getDescription(), description)) {
            configuration.changeDescription(description);
            changed = true;
        }
        if (configuration.getValueType() != request.valueType()
                || !valuesEqual(request.valueType(), configuration.getValue(), value)) {
            configuration.changeValue(request.valueType(), value);
            changed = true;
        }
        if (!tagIds(configuration.getTags()).equals(tagIds(tags))) {
            configuration.replaceTags(tags);
            changed = true;
        }

        if (changed) configurationRepository.flush();
        return ConfigurationMapper.toResponse(configuration);
    }

    @Transactional
    public void delete(Long id, long version) {
        ApplicationConfiguration configuration = find(id);
        verifyVersion(configuration.getVersion(), version, "Configuration");
        SystemConfigurationPolicy.validateDeletion(configuration);
        configurationRepository.delete(configuration);
        configurationRepository.flush();
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
