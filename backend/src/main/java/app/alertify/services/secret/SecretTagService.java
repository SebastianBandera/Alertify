package app.alertify.services.secret;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import app.alertify.jpa.repository.ApplicationSecretRepository;
import app.alertify.jpa.repository.TagRepository;
import app.alertify.jpa.specification.DynamicSpecification;
import app.alertify.jpa.specification.InvalidFilterException;
import app.alertify.logging.ApplicationEventLogger;

@Service
public class SecretTagService {

    private static final TagScope SCOPE = TagScope.SECRET;
    private static final Map<String, String> FILTER_ALIASES = Map.of("created", "createdAt", "modified", "updatedAt");
    private static final Set<String> FILTER_FIELDS = Set.of("id", "version", "name", "color", "createdAt", "updatedAt");
    private static final Set<String> SORT_FIELDS = Set.of("id", "version", "name", "color", "createdAt", "updatedAt");

    private final TagRepository tagRepository;
    private final ApplicationSecretRepository secretRepository;
    private final ApplicationEventLogger eventLogger;

    public SecretTagService(TagRepository tagRepository, ApplicationSecretRepository secretRepository, ApplicationEventLogger eventLogger) {
        this.tagRepository = tagRepository;
        this.secretRepository = secretRepository;
        this.eventLogger = eventLogger;
    }

    @Transactional(readOnly = true)
    public Page<TagResponse> search(MultiValueMap<String, String> params, Pageable pageable) {
        validateSort(pageable);
        Specification<Tag> scopeSpecification = (root, _, cb) -> cb.equal(root.get("scope"), SCOPE);
        Specification<Tag> filters = DynamicSpecification.from(params, FILTER_ALIASES, FILTER_FIELDS);
        return tagRepository.findAll(scopeSpecification.and(filters), pageable).map(SecretTagService::toResponse);
    }

    @Transactional(readOnly = true)
    public TagResponse get(Long id) {
        return toResponse(find(id));
    }

    @Transactional
    public TagResponse create(TagCreateRequest request) {
        String name = normalizeName(request.name());
        String color = normalizeColor(request.color());
        ensureNameAvailable(name, null);
        Tag saved = tagRepository.saveAndFlush(new Tag(SCOPE, name, color));
        eventLogger.successAfterCommit("SECRET_TAG_CREATED", Map.of("tagId", saved.getId(), "name", saved.getName(), "color", saved.getColor()));
        return toResponse(saved);
    }

    @Transactional
    public TagResponse update(Long id, TagUpdateRequest request) {
        Tag tag = find(id);
        ApplicationSecretService.verifyVersion(tag.getVersion(), request.version(), "Tag");
        String name = normalizeName(request.name());
        String color = normalizeColor(request.color());
        String previousName = tag.getName();
        String previousColor = tag.getColor();
        Set<String> changedFields = new LinkedHashSet<>();
        if (!tag.getName().equals(name)) {
            ensureNameAvailable(name, id);
            tag.rename(name);
            changedFields.add("name");
        }
        if (!tag.getColor().equals(color)) {
            tag.changeColor(color);
            changedFields.add("color");
        }
        if (!changedFields.isEmpty())
            tagRepository.flush();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tagId", id);
        data.put("name", tag.getName());
        data.put("previousName", previousName);
        data.put("color", tag.getColor());
        data.put("previousColor", previousColor);
        data.put("changed", !changedFields.isEmpty());
        data.put("changedFields", changedFields);
        eventLogger.successAfterCommit("SECRET_TAG_UPDATED", data);
        return toResponse(tag);
    }

    @Transactional
    public void delete(Long id, long version) {
        Tag tag = find(id);
        ApplicationSecretService.verifyVersion(tag.getVersion(), version, "Tag");
        if (secretRepository.existsByTagsId(id)) {
            throw new ConflictException(
                    "SECRET_TAG_IN_USE",
                    "Tag '" + tag.getName() + "' is assigned to one or more secrets",
                    Map.of("tagName", tag.getName())
            );
        }
        tagRepository.delete(tag);
        tagRepository.flush();
        eventLogger.successAfterCommit("SECRET_TAG_DELETED", Map.of("tagId", id, "name", tag.getName(), "color", tag.getColor(), "version", version));
    }

    private Tag find(Long id) {
        return tagRepository.findByIdAndScope(id, SCOPE).orElseThrow(() -> new ResourceNotFoundException("Secret tag " + id + " was not found"));
    }

    private void ensureNameAvailable(String name, Long currentId) {
        boolean exists = currentId == null
                ? tagRepository.existsByScopeAndNameIgnoreCase(SCOPE, name)
                : tagRepository.existsByScopeAndNameIgnoreCaseAndIdNot(SCOPE, name, currentId);
        if (exists)
            throw new ConflictException("A secret tag named '" + name + "' already exists");
    }

    private static void validateSort(Pageable pageable) {
        pageable.getSort().forEach(order -> {
            if (!SORT_FIELDS.contains(order.getProperty()))
                throw new InvalidFilterException("sort=" + order.getProperty());
        });
    }

    private static String normalizeName(String value) {
        return value.trim();
    }

    private static String normalizeColor(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static TagResponse toResponse(Tag tag) {
        return new TagResponse(tag.getId(), tag.getVersion(), tag.getScope(), tag.getName(), tag.getColor(), tag.getCreatedAt(), tag.getUpdatedAt());
    }
}
