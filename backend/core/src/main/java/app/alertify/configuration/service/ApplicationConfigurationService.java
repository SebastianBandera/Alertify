package app.alertify.configuration.service;

import java.io.IOException;
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
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;

import app.alertify.api.error.ConflictException;
import app.alertify.api.error.InvalidConfigurationImportException;
import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.configuration.api.ConfigurationCreateRequest;
import app.alertify.configuration.api.ConfigurationImportResult;
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

/**
 * Implements the administrative lifecycle of application configurations,
 * including search, validation, CSV import/export, tags, expressions, cache
 * invalidation and audit-oriented event logging.
 */
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
    private static final long MAX_IMPORT_FILE_SIZE = 50L * 1024 * 1024;

    private final ApplicationConfigurationRepository configurationRepository;
    private final TagRepository tagRepository;
    private final ConfigurationValueValidator valueValidator;
    private final ApplicationConfigurationLookupService lookupService;
    private final ConfigurationCacheInvalidator cacheInvalidator;
    private final ConfigurationCsvCodec csvCodec;
    private final ConfigurationExpressionService expressionService;
    private final ApplicationEventLogger eventLogger;

    public ApplicationConfigurationService(ApplicationConfigurationRepository configurationRepository, TagRepository tagRepository, ConfigurationValueValidator valueValidator, ApplicationConfigurationLookupService lookupService, ConfigurationCacheInvalidator cacheInvalidator, ConfigurationCsvCodec csvCodec, ConfigurationExpressionService expressionService, ApplicationEventLogger eventLogger) {
        this.configurationRepository = configurationRepository;
        this.tagRepository = tagRepository;
        this.valueValidator = valueValidator;
        this.lookupService = lookupService;
        this.cacheInvalidator = cacheInvalidator;
        this.csvCodec = csvCodec;
        this.expressionService = expressionService;
        this.eventLogger = eventLogger;
    }

    @Transactional(readOnly = true)
    public Page<ConfigurationResponse> search(MultiValueMap<String, String> params, Pageable pageable) {
        SearchValidation.validateSort(pageable, SORT_FIELDS);
        String valueContains = params.getFirst("valueContains");
        MultiValueMap<String, String> dynamicParams = new LinkedMultiValueMap<>(params.size());
        dynamicParams.addAll(params);
        dynamicParams.remove("valueContains");

        Specification<ApplicationConfiguration> specification = DynamicSpecification.from(dynamicParams, FILTER_ALIASES, FILTER_FIELDS);

        if (valueContains != null && !valueContains.isBlank()) {
            // Hidden values must not participate in value searches; otherwise
            // result membership could be used to infer a secret.
            specification = specification
                    .and(ApplicationConfigurationSpecifications.valueContains(valueContains))
                    .and(
                            ApplicationConfigurationSpecifications.nameNotEqualIgnoreCase(
                                    SystemConfigurationPolicy.KEY_PART
                            )
                    );
        }

        Set<Long> tagIds = parseTagIds(params.get("tagId"));
        boolean matchAllTags = parseMatchAllTags(params.get("tagOperator"));
        if (!tagIds.isEmpty()) {
            specification = specification.and(
                    matchAllTags
                            ? ApplicationConfigurationSpecifications.hasAllTagIds(tagIds)
                            : ApplicationConfigurationSpecifications.hasAnyTagId(tagIds)
            );
        }

        Page<ConfigurationResponse> result = configurationRepository.findAll(specification, pageable).map(ConfigurationMapper::toResponse);

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
        if (nameFilter != null && !nameFilter.isBlank())
            data.put("nameFilter", nameFilter);

        eventLogger.successAfterCommit("CONFIGURATION_PAGE_VIEWED", data);
        return result;
    }

    @Transactional(readOnly = true)
    public byte[] exportCsv() {
        List<ApplicationConfiguration> configurations = configurationRepository
                .findAll(Sort.by(Sort.Direction.ASC, "name"))
                .stream()
                .filter(configuration -> !SystemConfigurationPolicy.isValueHidden(configuration.getName()))
                .toList();
        return csvCodec.write(configurations);
    }

    @Transactional
    public ConfigurationImportResult importCsv(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidConfigurationImportException("A non-empty CSV file is required");
        }
        if (file.getSize() > MAX_IMPORT_FILE_SIZE) {
            throw new InvalidConfigurationImportException("CSV file exceeds the 50 MB limit");
        }

        List<ConfigurationCsvCodec.ImportRow> rows;
        try {
            rows = csvCodec.read(file.getBytes());
        } catch (IOException exception) {
            throw new InvalidConfigurationImportException("Unable to read the CSV file", exception);
        }

        Map<String, ApplicationConfiguration> configurationsByName = configurationRepository.findAll().stream()
                .collect(
                        Collectors.toMap(
                                configuration -> configuration.getName().toLowerCase(Locale.ROOT),
                                configuration -> configuration,
                                (left, _) -> left,
                                LinkedHashMap::new
                        )
                );

        Map<String, Tag> tagsByName = tagRepository.findAllByScope(TagScope.CONFIGURATION).stream()
                .collect(
                        Collectors.toMap(
                                tag -> tag.getName().toLowerCase(Locale.ROOT),
                                tag -> tag,
                                (left, _) -> left,
                                LinkedHashMap::new
                        )
                );

        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int tagsCreated = 0;

        for (ConfigurationCsvCodec.ImportRow row : rows) {
            if (SystemConfigurationPolicy.isSystemManaged(row.name())) {
                throw new InvalidConfigurationImportException(
                        "CSV row " + row.rowNumber() + ": configuration '" + SystemConfigurationPolicy.KEY_PART + "' cannot be imported"
                );
            }

            JsonNode value;
            try {
                value = valueValidator.validateAndNormalize(row.valueType(), row.value());
            } catch (RuntimeException exception) {
                throw new InvalidConfigurationImportException(
                        "CSV row " + row.rowNumber() + ": invalid value for " + row.valueType(),
                        exception
                );
            }

            Set<Tag> tags = new LinkedHashSet<>();
            for (ConfigurationCsvCodec.ImportTag importedTag : row.tags()) {
                String tagKey = importedTag.name().toLowerCase(Locale.ROOT);
                Tag tag = tagsByName.get(tagKey);
                if (tag == null) {
                    tag = tagRepository.save(
                            new Tag(
                                    TagScope.CONFIGURATION, importedTag.name(), importedTag.color()
                            )
                    );
                    tagsByName.put(tagKey, tag);
                    tagsCreated++;
                }
                tags.add(tag);
            }

            String configurationKey = row.name().toLowerCase(Locale.ROOT);
            ApplicationConfiguration configuration = configurationsByName.get(configurationKey);
            if (configuration == null) {
                ApplicationConfiguration createdConfiguration = configurationRepository.save(
                        new ApplicationConfiguration(
                                row.name(), row.description(), row.valueType(), value, tags
                        )
                );
                configurationsByName.put(configurationKey, createdConfiguration);
                created++;
                continue;
            }

            boolean changed = false;
            if (!configuration.getName().equals(row.name())) {
                configuration.rename(row.name());
                changed = true;
            }
            if (!Objects.equals(configuration.getDescription(), row.description())) {
                configuration.changeDescription(row.description());
                changed = true;
            }
            if (configuration.getValueType() != row.valueType() || !valuesEqual(row.valueType(), configuration.getValue(), value)) {
                configuration.changeValue(row.valueType(), value);
                changed = true;
            }
            if (!tagIds(configuration.getTags()).equals(tagIds(tags))) {
                configuration.replaceTags(tags);
                changed = true;
            }

            if (changed)
                updated++;
            else
                unchanged++;
        }

        if (created > 0 || updated > 0 || tagsCreated > 0) {
            configurationRepository.flush();
            tagRepository.flush();
            configurationsByName.values().forEach(expressionService::synchronizeDependencies);
            cacheInvalidator.clearAfterCommit();
        }

        ConfigurationImportResult result = new ConfigurationImportResult(
                rows.size(), created, updated, unchanged, tagsCreated
        );

        return result;
    }

    public ConfigurationResponse get(Long id) {
        ConfigurationResponse response = lookupService.getById(id);
        eventLogger.success("CONFIGURATION_VIEWED", Map.of("configurationId", response.id(), "name", response.name(), "version", response.version()));
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
        expressionService.synchronizeDependencies(saved);
        cacheInvalidator.evictAfterCommit(saved.getId(), Set.of(saved.getName()));
        eventLogger.successAfterCommit(
                "CONFIGURATION_CREATED",
                Map.of(
                        "configurationId", saved.getId(), "name", saved.getName(),
                        "valueType", saved.getValueType().name(), "tagIds", tagIds(saved.getTags())
                )
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
            expressionService.ensureNotReferenced(configuration, "renamed");
            ensureNameAvailable(name, id);
            configuration.rename(name);
            changedFields.add("name");
        }
        if (!Objects.equals(configuration.getDescription(), description)) {
            configuration.changeDescription(description);
            changedFields.add("description");
        }
        boolean valueTypeChanged = configuration.getValueType() != request.valueType();
        if (valueTypeChanged || !valuesEqual(request.valueType(), configuration.getValue(), value)) {
            configuration.changeValue(request.valueType(), value);
            changedFields.add("value");
            if (valueTypeChanged)
                changedFields.add("valueType");
        }
        if (!tagIds(configuration.getTags()).equals(tagIds(tags))) {
            configuration.replaceTags(tags);
            changedFields.add("tags");
        }

        if (!changedFields.isEmpty()) {
            configurationRepository.flush();
            if (changedFields.contains("value"))
                expressionService.synchronizeDependencies(configuration);

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
        expressionService.ensureNotReferenced(configuration, "deleted");
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
        return configurationRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Configuration " + id + " was not found"));
    }

    private void ensureNameAvailable(String name, Long currentId) {
        boolean exists = currentId == null
                ? configurationRepository.existsByNameIgnoreCase(name)
                : configurationRepository.existsByNameIgnoreCaseAndIdNot(name, currentId);
        if (exists)
            throw new ConflictException("A configuration named '" + name + "' already exists");
    }

    private Set<Tag> resolveConfigurationTags(Set<Long> requestedIds) {
        Set<Long> ids = requestedIds == null ? Set.of() : new LinkedHashSet<>(requestedIds);
        if (ids.isEmpty())
            return Set.of();

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
        if (rawValues == null || rawValues.isEmpty())
            return Set.of();

        try {
            return rawValues.stream()
                    .map(String::trim).map(Long::valueOf)
                    .peek(value -> {
                        if (value <= 0)
                            throw new NumberFormatException("Tag ID must be positive");
                    })
                    .collect(Collectors.toCollection(LinkedHashSet::new));
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

    private static boolean valuesEqual(ConfigurationValueType type, JsonNode currentValue, JsonNode requestedValue) {
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
        if (currentVersion != requestedVersion) {
            throw new ConflictException(
                    resourceName + " was modified by another request; reload it and try again"
            );
        }
    }
}
