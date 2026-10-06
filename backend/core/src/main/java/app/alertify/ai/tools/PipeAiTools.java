package app.alertify.ai.tools;

import java.util.List;
import java.util.UUID;

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
import app.alertify.pipes.api.PipeCreateRequest;
import app.alertify.pipes.api.PipeDeletionImpactResponse;
import app.alertify.pipes.api.PipeExecutionResponse;
import app.alertify.pipes.api.PipeImportResult;
import app.alertify.pipes.api.PipeOptionsResponse;
import app.alertify.pipes.api.PipeResponse;
import app.alertify.pipes.api.PipeUpdateRequest;
import app.alertify.pipes.service.PipeCsvService;
import app.alertify.pipes.service.PipeExecutionQueryService;
import app.alertify.pipes.service.PipeManagementService;
import app.alertify.pipes.service.PipeTagService;
import jakarta.validation.Valid;

@Component
@Validated
@PreAuthorize(AuthorizationPolicies.ADMIN)
public class PipeAiTools {

    private final PipeManagementService pipes;
    private final PipeCsvService csv;
    private final PipeExecutionQueryService executions;
    private final PipeTagService tags;
    private final AiToolExecutor tools;
    private final AiToolSupport support;

    public PipeAiTools(PipeManagementService pipes, PipeCsvService csv, PipeExecutionQueryService executions,
            PipeTagService tags, AiToolExecutor tools, AiToolSupport support) {
        this.pipes = pipes;
        this.csv = csv;
        this.executions = executions;
        this.tags = tags;
        this.tools = tools;
        this.support = support;
    }

    @Tool(name = "alertify_pipe_search", description = "Search pipe definitions with bounded pagination")
    public AiPage<PipeResponse> search(String name, @Valid AiPageRequest page) {
        return tools.execute("alertify_pipe_search", () -> AiPage.from(pipes.search(name, page.pageable())));
    }

    @Tool(name = "alertify_pipe_get", description = "Get one pipe definition by numeric ID")
    public PipeResponse get(long id) { return tools.execute("alertify_pipe_get", () -> pipes.get(id)); }

    @Tool(name = "alertify_pipe_options", description = "List resources and bindings available when defining a pipe")
    public PipeOptionsResponse options() { return tools.execute("alertify_pipe_options", pipes::options); }

    @Tool(name = "alertify_pipe_deletion_impact", description = "Preview references that affect deleting a pipe")
    public PipeDeletionImpactResponse deletionImpact(long id) {
        return tools.execute("alertify_pipe_deletion_impact", () -> pipes.deletionImpact(id));
    }

    @Tool(name = "alertify_pipe_create", description = "Create a pipe using administration validation")
    public PipeResponse create(@Valid PipeCreateRequest request) {
        return tools.execute("alertify_pipe_create", () -> pipes.create(request));
    }

    @Tool(name = "alertify_pipe_update", description = "Update a pipe using its current optimistic version")
    public PipeResponse update(long id, @Valid PipeUpdateRequest request) {
        return tools.execute("alertify_pipe_update", () -> pipes.update(id, request));
    }

    @Tool(name = "alertify_pipe_run", description = "Trigger an immediate pipe execution and return its execution ID")
    public UUID run(long id) { return tools.execute("alertify_pipe_run", () -> pipes.run(id)); }

    @Tool(name = "alertify_pipe_delete", description = "Delete a pipe using its current optimistic version")
    public void delete(long id, long version) { tools.execute("alertify_pipe_delete", () -> pipes.delete(id, version)); }

    @Tool(name = "alertify_pipe_export_csv", description = "Export pipes as a bounded UTF-8 CSV text file")
    public AiTextFile exportCsv() {
        return tools.execute("alertify_pipe_export_csv", () -> support.csv("alertify-pipes.csv", csv.exportCsv()));
    }

    @Tool(name = "alertify_pipe_import_csv", description = "Import pipes from a bounded UTF-8 CSV text file")
    public PipeImportResult importCsv(@Valid AiTextFile file) {
        return tools.execute("alertify_pipe_import_csv", () -> csv.importCsv(support.csvUpload(file)));
    }

    @Tool(name = "alertify_pipe_execution_search", description = "Search pipe execution history")
    public AiPage<PipeExecutionResponse> executionSearch(Long pipeId, @Valid AiPageRequest page) {
        return tools.execute("alertify_pipe_execution_search", () -> AiPage.from(executions.search(pipeId, page.pageable())));
    }

    @Tool(name = "alertify_pipe_execution_get", description = "Get one pipe execution by execution UUID")
    public PipeExecutionResponse executionGet(UUID executionId) {
        return tools.execute("alertify_pipe_execution_get", () -> executions.get(executionId));
    }

    @Tool(name = "alertify_pipe_tag_search", description = "Search tags scoped to pipes")
    public AiPage<TagResponse> tagSearch(List<AiFilter> filters, @Valid AiPageRequest page) {
        return tools.execute("alertify_pipe_tag_search", () -> AiPage.from(tags.search(support.filters(filters), page.pageable())));
    }

    @Tool(name = "alertify_pipe_tag_create", description = "Create a tag scoped to pipes")
    public TagResponse tagCreate(@Valid TagCreateRequest request) {
        return tools.execute("alertify_pipe_tag_create", () -> tags.create(request));
    }

    @Tool(name = "alertify_pipe_tag_update", description = "Update a pipe tag using its current version")
    public TagResponse tagUpdate(long id, @Valid TagUpdateRequest request) {
        return tools.execute("alertify_pipe_tag_update", () -> tags.update(id, request));
    }

    @Tool(name = "alertify_pipe_tag_delete", description = "Delete a pipe tag using its current version")
    public void tagDelete(long id, long version) { tools.execute("alertify_pipe_tag_delete", () -> tags.delete(id, version)); }
}
