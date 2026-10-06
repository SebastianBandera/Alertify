package app.alertify.ai.tools;

import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import app.alertify.ai.AiToolExecutor;
import app.alertify.ai.AiToolSupport;
import app.alertify.ai.api.AiFilter;
import app.alertify.ai.api.AiPage;
import app.alertify.ai.api.AiPageRequest;
import app.alertify.ai.api.AiTextFile;
import app.alertify.config.AuthorizationPolicies;
import app.alertify.configuration.api.TagCreateRequest;
import app.alertify.configuration.api.TagResponse;
import app.alertify.configuration.api.TagUpdateRequest;
import app.alertify.hooks.api.HookCreateRequest;
import app.alertify.hooks.api.HookDeletionImpactResponse;
import app.alertify.hooks.api.HookImportResult;
import app.alertify.hooks.api.HookInvocationResponse;
import app.alertify.hooks.api.HookOptionsResponse;
import app.alertify.hooks.api.HookResponse;
import app.alertify.hooks.api.HookUpdateRequest;
import app.alertify.hooks.service.HookCsvService;
import app.alertify.hooks.service.HookInvocationPersistenceService;
import app.alertify.hooks.service.HookManagementService;
import app.alertify.hooks.service.HookTagService;
import jakarta.validation.Valid;

@Component
@Validated
@PreAuthorize(AuthorizationPolicies.ADMIN)
public class HookAiTools {

    private final HookManagementService hooks;
    private final HookInvocationPersistenceService invocations;
    private final HookCsvService csv;
    private final HookTagService tags;
    private final AiToolExecutor tools;
    private final AiToolSupport support;

    public HookAiTools(HookManagementService hooks, HookInvocationPersistenceService invocations, HookCsvService csv,
            HookTagService tags, AiToolExecutor tools, AiToolSupport support) {
        this.hooks = hooks;
        this.invocations = invocations;
        this.csv = csv;
        this.tags = tags;
        this.tools = tools;
        this.support = support;
    }

    @Tool(name = "alertify_hook_search", description = "Search hook definitions with bounded pagination")
    public AiPage<HookResponse> search(String name, @Valid AiPageRequest page) {
        return tools.execute("alertify_hook_search", () -> AiPage.from(hooks.search(name, page.pageable())));
    }

    @Tool(name = "alertify_hook_get", description = "Get one hook definition without exposing any token value")
    public HookResponse get(long id) { return tools.execute("alertify_hook_get", () -> hooks.get(id)); }

    @Tool(name = "alertify_hook_options", description = "List resources and secret references available when defining a hook")
    public HookOptionsResponse options() { return tools.execute("alertify_hook_options", hooks::options); }

    @Tool(name = "alertify_hook_deletion_impact", description = "Preview references that affect deleting a hook")
    public HookDeletionImpactResponse deletionImpact(long id) {
        return tools.execute("alertify_hook_deletion_impact", () -> hooks.deletionImpact(id));
    }

    @Tool(name = "alertify_hook_create", description = "Create a hook referencing an existing token secret; does not create or reveal credentials")
    public HookResponse create(@Valid HookCreateRequest request) {
        return tools.execute("alertify_hook_create", () -> hooks.create(request));
    }

    @Tool(name = "alertify_hook_update", description = "Update a hook using its current optimistic version; token rotation is unavailable")
    public HookResponse update(long id, @Valid HookUpdateRequest request) {
        return tools.execute("alertify_hook_update", () -> hooks.update(id, request));
    }

    @Tool(name = "alertify_hook_delete", description = "Delete a hook using its current optimistic version")
    public void delete(long id, long version) { tools.execute("alertify_hook_delete", () -> hooks.delete(id, version)); }

    @Tool(name = "alertify_hook_invocation_history", description = "Read execution history for an administratively selected hook")
    public AiPage<HookInvocationResponse> invocationHistory(long id, @Valid AiPageRequest page) {
        return tools.execute("alertify_hook_invocation_history", () -> {
            hooks.get(id);
            return AiPage.from(invocations.history(id, page.pageable()));
        });
    }

    @Tool(name = "alertify_hook_export_csv", description = "Export hooks as bounded UTF-8 CSV without secret values")
    public AiTextFile exportCsv() {
        return tools.execute("alertify_hook_export_csv", () -> support.csv("alertify-hooks.csv", csv.exportCsv()));
    }

    @Tool(name = "alertify_hook_import_csv", description = "Import hooks from bounded UTF-8 CSV; referenced secrets must already exist")
    public HookImportResult importCsv(@Valid AiTextFile file) {
        return tools.execute("alertify_hook_import_csv", () -> csv.importCsv(support.csvUpload(file)));
    }

    @Tool(name = "alertify_hook_tag_search", description = "Search tags scoped to hooks")
    public AiPage<TagResponse> tagSearch(List<AiFilter> filters, @Valid AiPageRequest page) {
        return tools.execute("alertify_hook_tag_search", () -> AiPage.from(tags.search(support.filters(filters), page.pageable())));
    }

    @Tool(name = "alertify_hook_tag_create", description = "Create a tag scoped to hooks")
    public TagResponse tagCreate(@Valid TagCreateRequest request) {
        return tools.execute("alertify_hook_tag_create", () -> tags.create(request));
    }

    @Tool(name = "alertify_hook_tag_update", description = "Update a hook tag using its current version")
    public TagResponse tagUpdate(long id, @Valid TagUpdateRequest request) {
        return tools.execute("alertify_hook_tag_update", () -> tags.update(id, request));
    }

    @Tool(name = "alertify_hook_tag_delete", description = "Delete a hook tag using its current version")
    public void tagDelete(long id, long version) { tools.execute("alertify_hook_tag_delete", () -> tags.delete(id, version)); }
}
