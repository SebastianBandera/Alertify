package app.alertify.ai.memory;

import java.time.Instant;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import app.alertify.ai.api.AiPage;
import app.alertify.api.error.ConflictException;
import app.alertify.api.error.ResourceNotFoundException;
import app.alertify.config.AuthorizationPolicies;
import app.alertify.logging.ApplicationEventLogger;

/** Administrative access to all owners, independent of model memory settings and worker availability. */
@Service
@PreAuthorize(AuthorizationPolicies.ADMIN)
@Transactional(readOnly = true)
public class AiMemoryManagementService {
    private final AiMemoryRepository memories;
    private final ApplicationEventLogger events;

    public AiMemoryManagementService(AiMemoryRepository memories, ApplicationEventLogger events) {
        this.memories = memories;
        this.events = events;
    }

    public AiPage<Summary> list(int page, int size) {
        if (page < 0 || size < 1 || size > 50)
            throw new IllegalArgumentException("Use a non-negative page and a size between 1 and 50");

        return AiPage.from(memories.findAllByOrderByUpdatedAtDescIdDesc(PageRequest.of(page, size))
                .map(value -> new Summary(value.getId(), value.getUserSubject(), value.getMemoryKey(), value.getVersion(), value.getCreatedAt(), value.getUpdatedAt())));
    }

    public Detail get(Long id) {
        AiMemory memory = find(id);
        return new Detail(memory.getId(), memory.getUserSubject(), memory.getMemoryKey(), memory.getContent(), memory.getVersion(), memory.getSourceConversationId(), memory.getCreatedAt(), memory.getUpdatedAt());
    }

    @Transactional
    public void delete(Long id, long version) {
        AiMemory memory = find(id);
        if (version < 0 || memory.getVersion() != version)
            throw ConflictException.versionConflict("The memory changed; reload it before deleting");

        memories.delete(memory);
        memories.flush();
        events.successAfterCommit("AI_MEMORY_DELETED", Map.of("memoryId", id, "version", version, "management", true));
    }

    @Transactional
    public int deleteAll() {
        int count = memories.deleteAllMemories();
        events.successAfterCommit("AI_MEMORY_DELETED", Map.of("deletedCount", count, "all", true, "management", true));
        return count;
    }

    private AiMemory find(Long id) {
        return memories.findById(id).orElseThrow(() -> new ResourceNotFoundException("AI memory not found"));
    }

    public record Summary(Long id, String userSubject, String key, long version, Instant createdAt, Instant updatedAt) {
    }

    public record Detail(Long id, String userSubject, String key, String content, long version, Long sourceConversationId, Instant createdAt, Instant updatedAt) {
    }
}
