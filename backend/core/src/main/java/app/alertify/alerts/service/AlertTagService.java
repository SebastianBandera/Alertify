package app.alertify.alerts.service;

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
import app.alertify.jpa.repository.AlertRepository;
import app.alertify.jpa.repository.TagRepository;
import app.alertify.jpa.specification.DynamicSpecification;
import app.alertify.jpa.specification.InvalidFilterException;
import app.alertify.logging.ApplicationEventLogger;

@Service
public class AlertTagService {

    private static final TagScope SCOPE = TagScope.ALERT;
    private static final Map<String, String> FILTER_ALIASES = Map.of("created", "createdAt", "modified", "updatedAt");
    private static final Set<String> FILTER_FIELDS = Set.of("id", "version", "name", "color", "createdAt", "updatedAt");
    private static final Set<String> SORT_FIELDS = Set.of("id", "version", "name", "color", "createdAt", "updatedAt");

    private final TagRepository tagRepository;
    private final AlertRepository alertRepository;
    private final ApplicationEventLogger eventLogger;

    public AlertTagService(TagRepository tagRepository, AlertRepository alertRepository, ApplicationEventLogger eventLogger) {
        this.tagRepository = tagRepository;
        this.alertRepository = alertRepository;
        this.eventLogger = eventLogger;
    }

    @Transactional(readOnly = true)
    public Page<TagResponse> search(MultiValueMap<String, String> params, Pageable pageable) {
        validateSort(pageable);
        Specification<Tag> scope = (root, _, cb) -> cb.equal(root.get("scope"), SCOPE);
        return tagRepository.findAll(scope.and(DynamicSpecification.from(params, FILTER_ALIASES, FILTER_FIELDS)), pageable)
                .map(AlertTagService::toResponse);
    }

    @Transactional
    public TagResponse create(TagCreateRequest request) {
        String name = request.name().trim();
        ensureNameAvailable(name, null);
        Tag saved = tagRepository.saveAndFlush(new Tag(SCOPE, name, normalizeColor(request.color())));
        eventLogger.successAfterCommit("ALERT_TAG_CREATED", Map.of("tagId", saved.getId(), "name", saved.getName()));
        return toResponse(saved);
    }

    @Transactional
    public TagResponse update(Long id, TagUpdateRequest request) {
        Tag tag = find(id);
        ensureVersion(tag, request.version());
        String name = request.name().trim();
        String color = normalizeColor(request.color());
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
        data.put("changedFields", changedFields);
        eventLogger.successAfterCommit("ALERT_TAG_UPDATED", data);
        return toResponse(tag);
    }

    @Transactional
    public void delete(Long id, long version) {
        Tag tag = find(id);
        ensureVersion(tag, version);
        if (alertRepository.existsByTagsId(id)) {
            throw new ConflictException(
                    "ALERT_TAG_IN_USE",
                    "Tag '" + tag.getName() + "' is assigned to one or more alerts",
                    Map.of("tagName", tag.getName())
            );
        }
        tagRepository.delete(tag);
        tagRepository.flush();
        eventLogger.successAfterCommit("ALERT_TAG_DELETED", Map.of("tagId", id, "name", tag.getName()));
    }

    private Tag find(Long id) {
        return tagRepository.findByIdAndScope(id, SCOPE)
                .orElseThrow(() -> new ResourceNotFoundException("Alert tag " + id + " was not found"));
    }

    private void ensureNameAvailable(String name, Long currentId) {
        boolean exists = currentId == null
                ? tagRepository.existsByScopeAndNameIgnoreCase(SCOPE, name)
                : tagRepository.existsByScopeAndNameIgnoreCaseAndIdNot(SCOPE, name, currentId);
        if (exists)
            throw new ConflictException("An alert tag named '" + name + "' already exists");
    }

    private static void ensureVersion(Tag tag, long version) {
        if (tag.getVersion() != version)
            throw new ConflictException("Tag was modified by another request; reload it and try again");
    }

    private static void validateSort(Pageable pageable) {
        pageable.getSort().forEach(order -> {
            if (!SORT_FIELDS.contains(order.getProperty()))
                throw new InvalidFilterException("sort=" + order.getProperty());
        });
    }

    private static String normalizeColor(String color) {
        return color.trim().toUpperCase(Locale.ROOT);
    }

    private static TagResponse toResponse(Tag tag) {
        return new TagResponse(tag.getId(), tag.getVersion(), tag.getScope(), tag.getName(), tag.getColor(), tag.getCreatedAt(), tag.getUpdatedAt());
    }
}
