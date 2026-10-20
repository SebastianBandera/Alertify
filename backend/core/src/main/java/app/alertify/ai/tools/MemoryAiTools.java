package app.alertify.ai.tools;

import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

import app.alertify.ai.AiInvocationContext;
import app.alertify.ai.AiInvocationContextHolder;
import app.alertify.ai.AiRefreshTarget;
import app.alertify.ai.AiToolExecutor;
import app.alertify.ai.AlertifyTool;
import app.alertify.ai.memory.AiMemoryService;
import app.alertify.ai.memory.AiMemoryAccess;
import app.alertify.codex.AiWorkerClient;
import app.alertify.config.AuthorizationPolicies;

@Component
@PreAuthorize(AuthorizationPolicies.ADMIN)
public class MemoryAiTools {
    public static final String SAVE_TOOL = "alertify_memory_save";
    public static final String SEARCH_TOOL = "alertify_memory_search";
    public static final String DELETE_TOOL = "alertify_memory_delete";
    private final AiMemoryService memories;
    private final AiToolExecutor tools;
    private final AiWorkerClient worker;

    public MemoryAiTools(AiMemoryService memories, AiToolExecutor tools, AiWorkerClient worker) {
        this.memories = memories;
        this.tools = tools;
        this.worker = worker;
    }

    @AlertifyTool(name = SAVE_TOOL, description = "Create or update a private memory for the authenticated user. " + AiMemoryService.CAPTURE_RULES + " New keys must be descriptive lowercase ASCII names, up to 96 characters, with dot/underscore/hyphen separators. Content is limited to 4000 characters. version=null creates a new key only; an existing key requires its freshly read version. Saving does not execute procedures or change resources described in the memory.", readOnly = false, refreshTargets = AiRefreshTarget.MEMORIES)
    public AiMemoryService.Memory save(@ToolParam(description = "Descriptive new key or exact existing key copied from memory_search. Example: maven.protobuf.windows. Never supply another user's ID.") String key, @ToolParam(description = "Concise preference or reusable knowledge; for verified corrections include the problem, confirmed cause, solution and applicable scope. Never include secrets or raw diagnostics.") String content, @ToolParam(required = false, description = "null to create; otherwise the current version copied from memory_search, including 0 when returned. Never guess or replay a stale version.") Long version) {
        return tools.execute(SAVE_TOOL, () -> {
            AiMemoryAccess.requireEnabled(worker);
            var context = context();
            return memories.save(context.userSubject(), context.conversationId(), key, content, version);
        });
    }

    @AlertifyTool(name = SEARCH_TOOL, description = "Search the authenticated user's private memories by literal case-insensitive text in key or content. An empty query lists memories. Returns full content, key, version, UTC dates and origin conversation. limit defaults to 10, maximum 20; pages are also bounded by JSON byte size. Repeat the same query with nextCursor for more results. Match an exact returned key when verifying a save/deletion; other substring matches are different memories. " + AiMemoryService.CONTEXT_RULES, readOnly = true, idempotentHint = true)
    public AiMemoryService.SearchResult search(@ToolParam(description = "Literal phrase or memory key from the index. Empty string lists all. Maximum 256 characters; wildcards are literal.") String query, @ToolParam(required = false, description = "Optional limit from 1 to 20; null uses 10.") Integer limit, @ToolParam(required = false, description = "Opaque nextCursor for this same user and query, or null for the first page.") String cursor) {
        return tools.execute(SEARCH_TOOL, () -> {
            AiMemoryAccess.requireEnabled(worker);
            return memories.search(context().userSubject(), query, limit, cursor);
        });
    }

    @AlertifyTool(name = DELETE_TOOL, description = "Forget one private memory belonging to the authenticated user, using its exact key and current version from memory_search. Inspect the current content and follow the existing approval policy before deletion. This does not delete its source chat or any described resource. After success, search to verify the exact key is absent.", readOnly = false, destructiveHint = true, refreshTargets = AiRefreshTarget.MEMORIES)
    public AiMemoryService.Deleted delete(@ToolParam(description = "Exact memory key copied from memory_search.") String key, @ToolParam(description = "Current version from memory_search, including 0 when returned.") long version) {
        return tools.execute(DELETE_TOOL, () -> {
            AiMemoryAccess.requireEnabled(worker);
            return memories.delete(context().userSubject(), key, version);
        });
    }

    public static boolean isMemoryTool(String name) {
        return SAVE_TOOL.equals(name) || SEARCH_TOOL.equals(name) || DELETE_TOOL.equals(name);
    }

    private static AiInvocationContext context() {
        var context = AiInvocationContextHolder.current();
        if (context == null)
            throw new AccessDeniedException("Authenticated memory context is required");

        return context;
    }
}
