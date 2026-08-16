package app.alertify.configuration.service;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MultiValueMap;

import app.alertify.api.error.ConflictException;
import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.configuration.api.TagCreateRequest;
import app.alertify.configuration.api.TagResponse;
import app.alertify.configuration.api.TagUpdateRequest;
import app.alertify.jpa.entity.Tag;
import app.alertify.jpa.entity.TagScope;
import app.alertify.jpa.repository.ApplicationConfigurationRepository;
import app.alertify.jpa.repository.TagRepository;
import app.alertify.jpa.specification.DynamicSpecification;

@Service
public class ConfigurationTagService {

    private static final TagScope SCOPE = TagScope.CONFIGURATION;
    private static final Map<String, String> FILTER_ALIASES = Map.of(
        "created", "createdAt", "modified", "updatedAt"
    );
    private static final Set<String> FILTER_FIELDS = Set.of(
        "id", "version", "name", "color", "createdAt", "updatedAt"
    );
    private static final Set<String> SORT_FIELDS = Set.of(
        "id", "version", "name", "color", "createdAt", "updatedAt"
    );

    private final TagRepository tagRepository;
    private final ApplicationConfigurationRepository configurationRepository;

    public ConfigurationTagService(
            TagRepository tagRepository,
            ApplicationConfigurationRepository configurationRepository) {
        this.tagRepository = tagRepository;
        this.configurationRepository = configurationRepository;
    }

    @Transactional(readOnly = true)
    public Page<TagResponse> search(MultiValueMap<String, String> params, Pageable pageable) {
        SearchValidation.validateSort(pageable, SORT_FIELDS);
        Specification<Tag> scopeSpecification = (root, query, cb) ->
            cb.equal(root.get("scope"), SCOPE);
        Specification<Tag> filters = DynamicSpecification.from(params, FILTER_ALIASES, FILTER_FIELDS);
        return tagRepository.findAll(scopeSpecification.and(filters), pageable)
            .map(ConfigurationMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public TagResponse get(Long id) { return ConfigurationMapper.toResponse(find(id)); }

    @Transactional
    public TagResponse create(TagCreateRequest request) {
        String name = normalizeName(request.name());
        String color = normalizeColor(request.color());
        ensureNameAvailable(name, null);
        return ConfigurationMapper.toResponse(tagRepository.saveAndFlush(new Tag(SCOPE, name, color)));
    }

    @Transactional
    public TagResponse update(Long id, TagUpdateRequest request) {
        Tag tag = find(id);
        ApplicationConfigurationService.verifyVersion(tag.getVersion(), request.version(), "Tag");

        String name = normalizeName(request.name());
        String color = normalizeColor(request.color());
        boolean changed = false;

        if (!tag.getName().equals(name)) {
            ensureNameAvailable(name, id);
            tag.rename(name);
            changed = true;
        }
        if (!tag.getColor().equals(color)) {
            tag.changeColor(color);
            changed = true;
        }
        if (changed) tagRepository.flush();
        return ConfigurationMapper.toResponse(tag);
    }

    @Transactional
    public void delete(Long id, long version) {
        Tag tag = find(id);
        ApplicationConfigurationService.verifyVersion(tag.getVersion(), version, "Tag");
        if (configurationRepository.existsByTagsId(id)) {
            throw new ConflictException("Tag " + id + " is assigned to one or more configurations");
        }
        tagRepository.delete(tag);
        tagRepository.flush();
    }

    private Tag find(Long id) {
        return tagRepository.findByIdAndScope(id, SCOPE)
            .orElseThrow(() -> new ResourceNotFoundException("Configuration tag " + id + " was not found"));
    }

    private void ensureNameAvailable(String name, Long currentId) {
        boolean exists = currentId == null
            ? tagRepository.existsByScopeAndNameIgnoreCase(SCOPE, name)
            : tagRepository.existsByScopeAndNameIgnoreCaseAndIdNot(SCOPE, name, currentId);
        if (exists) throw new ConflictException("A configuration tag named '" + name + "' already exists");
    }

    private static String normalizeName(String value) { return value.trim(); }
    private static String normalizeColor(String value) { return value.trim().toUpperCase(Locale.ROOT); }
}
