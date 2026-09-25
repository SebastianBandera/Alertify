package app.alertify.pipes.service;

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
import app.alertify.jpa.repository.PipeRepository;
import app.alertify.jpa.repository.TagRepository;
import app.alertify.jpa.specification.DynamicSpecification;
import app.alertify.jpa.specification.InvalidFilterException;
import app.alertify.logging.ApplicationEventLogger;

@Service
public class PipeTagService {
    private static final String TAG_ID = "tagId";
    private static final TagScope SCOPE = TagScope.PIPE;
    private static final Map<String, String> FILTER_ALIASES = Map.of("created", "createdAt", "modified", "updatedAt");
    private static final Set<String> FIELDS = Set.of("id", "version", "name", "color", "createdAt", "updatedAt");
    private final TagRepository tags;
    private final PipeRepository pipes;
    private final ApplicationEventLogger eventLogger;

    public PipeTagService(TagRepository tags, PipeRepository pipes, ApplicationEventLogger eventLogger) {
        this.tags = tags;
        this.pipes = pipes;
        this.eventLogger = eventLogger;
    }

    @Transactional(readOnly = true)
    public Page<TagResponse> search(MultiValueMap<String, String> params, Pageable pageable) {
        pageable.getSort().forEach(order -> {
            if (!FIELDS.contains(order.getProperty()))
                throw new InvalidFilterException("sort=" + order.getProperty());
        });
        Specification<Tag> scope = (root, _, cb) -> cb.equal(root.get("scope"), SCOPE);
        return tags.findAll(scope.and(DynamicSpecification.from(params, FILTER_ALIASES, FIELDS)), pageable).map(PipeTagService::response);
    }

    @Transactional
    public TagResponse create(TagCreateRequest request) {
        String name = request.name().trim();
        ensureAvailable(name, null);
        Tag tag = tags.saveAndFlush(new Tag(SCOPE, name, request.color().trim().toUpperCase(Locale.ROOT)));
        eventLogger.successAfterCommit("PIPE_TAG_CREATED", Map.of(TAG_ID, tag.getId(), "name", tag.getName()));
        return response(tag);
    }

    @Transactional
    public TagResponse update(Long id, TagUpdateRequest request) {
        Tag tag = find(id);
        if (tag.getVersion() != request.version())
            throw new ConflictException("Tag was modified by another request; reload it and try again");

        String name = request.name().trim();
        ensureAvailable(name, id);
        tag.rename(name);
        tag.changeColor(request.color().trim().toUpperCase(Locale.ROOT));
        tags.flush();
        eventLogger.successAfterCommit("PIPE_TAG_UPDATED", Map.of(TAG_ID, id, "name", name));
        return response(tag);
    }

    @Transactional
    public void delete(Long id, long version) {
        Tag tag = find(id);
        if (tag.getVersion() != version)
            throw new ConflictException("Tag was modified by another request; reload it and try again");

        if (pipes.existsByTagsId(id))
            throw new ConflictException("PIPE_TAG_IN_USE", "Tag '" + tag.getName() + "' is assigned to pipes", Map.of("tagName", tag.getName()));

        tags.delete(tag);
        tags.flush();
        eventLogger.successAfterCommit("PIPE_TAG_DELETED", Map.of(TAG_ID, id, "name", tag.getName()));
    }

    private Tag find(Long id) { return tags.findByIdAndScope(id, SCOPE).orElseThrow(() -> new ResourceNotFoundException("Pipe tag " + id + " was not found")); }

    private void ensureAvailable(String name, Long id) {
        boolean exists = id == null ? tags.existsByScopeAndNameIgnoreCase(SCOPE, name) : tags.existsByScopeAndNameIgnoreCaseAndIdNot(SCOPE, name, id);
        if (exists)
            throw new ConflictException("A Pipe tag named '" + name + "' already exists");
    }

    private static TagResponse response(Tag tag) { return new TagResponse(tag.getId(), tag.getVersion(), tag.getScope(), tag.getName(), tag.getColor(), tag.getCreatedAt(), tag.getUpdatedAt()); }
}
